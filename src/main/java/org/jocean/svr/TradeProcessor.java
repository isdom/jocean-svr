/**
 *
 */
package org.jocean.svr;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.jocean.http.FullMessage;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.server.internal.DefaultHttpTrade;
import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Tuple;
import org.jocean.idiom.jmx.MBeanRegister;
import org.jocean.idiom.jmx.MBeanRegisterAware;
import org.jocean.svr.mbean.RestinIndicator;
import org.jocean.svr.tracing.TraceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import com.google.common.collect.ImmutableMap;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.noop.NoopTracerFactory;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.Subscription;
import rx.schedulers.Schedulers;

/**
 * @author isdom
 *
 */
public class TradeProcessor extends Subscriber<HttpTrade> implements MBeanRegisterAware {

    private static final Logger LOG = LoggerFactory.getLogger(TradeProcessor.class);

    public TradeProcessor(final Registrar registrar, final RestinIndicator restin) {
        this._registrar = registrar;
        this._restin = restin;
    }

    @Override
    public void onCompleted() {
    }

    @Override
    public void onError(final Throwable e) {
        LOG.warn("fatal error with {} restin.", ExceptionUtils.exception2detail(e));
    }

    @Override
    public void onNext(final HttpTrade trade) {
        trade.inbound().first().flatMap(fhr ->
            getTradeScheduler().flatMap(ts ->
                getTracer().subscribeOn(ts.scheduler()).map(tracer -> {
                    Tracer.SpanBuilder spanBuilder;
                    try {
                        final SpanContext parentSpanCtx = tracer.extract(Format.Builtin.HTTP_HEADERS,
                                TraceUtil.message2textmap(fhr.message()));
                        if (parentSpanCtx == null) {
                            spanBuilder = tracer.buildSpan("(unknown)");
                        } else {
                            spanBuilder = tracer.buildSpan("(unknown)").asChildOf(parentSpanCtx);
                        }
                    } catch (final IllegalArgumentException e) {
                        spanBuilder = tracer.buildSpan("(unknown)");
                    }
                    final HttpRequest request = fhr.message();

                    return Tuple.of(ts, fhr, tracer, spanBuilder.withTag(Tags.COMPONENT.getKey(), "jocean-http")
                        .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                        .withTag(Tags.HTTP_URL.getKey(), request.uri())
                        .withTag(Tags.HTTP_METHOD.getKey(), request.method().name())
//                        .withTag(Tags.PEER_HOST_IPV4.getKey(), "")
                        .start());
            }))).subscribe( ts_req_tracer_span -> {
                final TradeScheduler ts = ts_req_tracer_span.getAt(0);
                final FullMessage<HttpRequest> fullreq = ts_req_tracer_span.getAt(1);
                final Tracer tracer = ts_req_tracer_span.getAt(2);
                final Span span = ts_req_tracer_span.getAt(3);

                // log req's headers
                span.log(Collections.singletonMap("http.req.hdrs", fullreq.message().headers().toString()));

                TraceUtil.hook4serversend(trade.writeCtrl(), span);
                TraceUtil.logoutmsg(trade.writeCtrl(), span, "http.resp", 160);

                TraceUtil.addTagNotNull(span, "http.host", fullreq.message().headers().get(HttpHeaderNames.HOST));
                {
                    final String ips = fullreq.message().headers().get("x-forwarded-for");
                    if (null != ips) {
                        TraceUtil.addTagNotNull(span, Tags.PEER_HOST_IPV4.getKey(), ips.split(",")[0]);
                    }
                }

                for (final String tag : this.headerTags) {
                    TraceUtil.addTagNotNull(span, tag, fullreq.message().headers().get(tag));
                }

                handleTrade(fullreq, trade, tracer, span, ts);
            }, e -> LOG.warn("SOURCE_CANCELED\nfor cause:[{}]", ExceptionUtils.exception2detail(e)));
    }

    private Observable<TradeScheduler> getTradeScheduler() {
        return _finder.find(this._schedulerName, TradeScheduler.class).onErrorReturn(e -> _DEFAULT_TS);
    }

    private Observable<Tracer> getTracer() {
        return this._tracingEnabled ? this._finder.find(Tracer.class).onErrorReturn(e -> noopTracer)
                : Observable.just(noopTracer);
    }

    private boolean isWaitInboundCompleted(final FullMessage<HttpRequest> fhr) {
        if (HttpUtil.isTransferEncodingChunked(fhr.message())) {
            LOG.info("chunked request, handle raw {}.", fhr);
            return false;
        }
        if (!HttpUtil.isContentLengthSet(fhr.message())) {
            LOG.info("content-length not set, handle raw {}.", fhr);
            return false;
        }
        final long contentLength = HttpUtil.getContentLength(fhr.message());
        if (contentLength <= this._maxContentLengthForCacheInbound) {
            LOG.info("content-length is {} <= {}, wait inbound completed for {}.",
                    contentLength, this._maxContentLengthForCacheInbound, fhr);
            return true;
        } else {
            LOG.info("content-length is {} > {}, handle raw {}.",
                    contentLength, this._maxContentLengthForCacheInbound, fhr);
            return false;
        }
    }

    private Observable<HttpTrade> waitInboundCompletedIfNeed(final HttpTrade trade, final FullMessage<HttpRequest> fhr, final TradeScheduler ts) {
        if ( this._maxContentLengthForCacheInbound <= 0) {
            LOG.info("disable autoread full request, handle raw {}.", trade);
            return Observable.just(trade);
        } else if (!isWaitInboundCompleted(fhr)) {
            return Observable.just(trade);
        } else if (!(trade instanceof DefaultHttpTrade)) {
            return Observable.just(trade);
        } else {
            return ((DefaultHttpTrade)trade).waitInboundCompleted().cast(HttpTrade.class).observeOn(ts.scheduler());
        }
    }

    private void handleTrade(final FullMessage<HttpRequest> fullreq,
            final HttpTrade trade,
            final Tracer tracer,
            final Span span,
            final TradeScheduler ts) {
        final Subscription cancelTradetimeout = Observable.timer(_tradeTimeout, TimeUnit.SECONDS).subscribe(any -> {
            // TODO, terminate trade and record more info
            span.setTag(Tags.ERROR.getKey(), true);
            trade.visitlogs((timestamp, fields) -> span.log(timestamp, fields));
            span.log(ImmutableMap.<String, Object>builder()
                    .put("content.size", trade.inboundContentSize())
                    .put("inbound", trade.inboundTracing())
                    .put("timeout", trade)
                    .build());
            span.finish();
        });

        trade.doOnHalt(() -> {
            // cancel timeout for trade
            cancelTradetimeout.unsubscribe();
            span.log(ImmutableMap.<String, Object>builder()
                    .put("content.size", trade.inboundContentSize())
                    .put("inbound", trade.inboundTracing())
                    .build());
            span.finish();
        });

        try {
            final Observable<? extends Object> outbound = waitInboundCompletedIfNeed(trade, fullreq, ts).flatMap(wicTrade ->
                {
                    try {
                        return this._registrar.buildResource(fullreq.message(), wicTrade, tracer, span, ts, this._restin);
                    } catch (final Exception e) {
                        return Observable.error(e);
                    }
                });
            trade.outbound(outbound.doOnNext(DisposableWrapperUtil.disposeOnForAny(trade)).doOnError(error -> {
                span.setTag(Tags.ERROR.getKey(), true);
                span.log(ImmutableMap.<String, Object>builder()
                        .put("content.size", trade.inboundContentSize())
                        .put("inbound", trade.inboundTracing())
                        .put("exception", ExceptionUtils.exception2detail(error))
                        .build());
            }));
        } catch (final Exception e) {
            LOG.warn("exception when buildResource, detail:{}",
                    ExceptionUtils.exception2detail(e));
        }
    }

    @Value("${http.hdr.tags}")
    void setHeaderTags(final String tags) {
        this.headerTags = tags.split(",");
    }

    private static volatile Tracer noopTracer = NoopTracerFactory.create();

    String[] headerTags = new String[0];

    @Inject
    BeanFinder _finder;

    // 事务超时时间(秒)
    @Value("${trade.timeoutInSeconds}")
    long _tradeTimeout = 30;

    @Value("${tracing.enabled}")
    boolean _tracingEnabled = true;

    @Value("${scheduler.name}")
    String _schedulerName = "scheduler_default";

    private final RestinIndicator _restin;

    private final Registrar _registrar;

    @Value("${cacheInbound.maxContentLength}")
    int _maxContentLengthForCacheInbound = 8192;

    private final static TradeScheduler _DEFAULT_TS = new TradeScheduler() {
        @Override
        public String toString() {
            return "immediate-TradeScheduler";
        }

        @Override
        public Scheduler scheduler() {
            return Schedulers.immediate();
        }
        @Override
        public int workerCount() {
            return 1;
        }};
    @Override
    public void setMBeanRegister(final MBeanRegister register) {
//        register.registerMBean("name=tradeProcessor", this);
    }

}
