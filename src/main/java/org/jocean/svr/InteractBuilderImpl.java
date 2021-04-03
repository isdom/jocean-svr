package org.jocean.svr;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.Path;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.ContentDecoder;
import org.jocean.http.ContentEncoder;
import org.jocean.http.Feature;
import org.jocean.http.FullMessage;
import org.jocean.http.Interact;
import org.jocean.http.InteractBuilder;
import org.jocean.http.MessageBody;
import org.jocean.http.MessageUtil;
import org.jocean.http.WriteCtrl;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.HttpClient.InitiatorBuilder;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Haltable;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.opentracing.DurationRecorder;
import org.jocean.svr.tracing.TraceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslContextBuilder;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Scheduler;
import rx.functions.Action1;

class InteractBuilderImpl implements InteractBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(InteractBuilderImpl.class);

    private static final Feature F_SSL;
    static {
        F_SSL = defaultSslFeature();
    }

    private static Feature defaultSslFeature() {
        try {
            return new Feature.ENABLE_SSL(SslContextBuilder.forClient().build());
        } catch (final Exception e) {
            LOG.error("exception init default ssl feature, detail: {}", ExceptionUtils.exception2detail(e));
            return null;
        }
    }

    public InteractBuilderImpl(
            final Haltable haltable,
            final Span span,
            final Observable<Tracer> getTracer,
            final Scheduler scheduler,
            // final Context asyncContext,
            final DurationRecorder durationRecorder,
            final TrafficRecorder tafficRecorder) {
        this._haltable = haltable;
        this._span = span;
        this._getTracer = getTracer;
        this._scheduler = scheduler;
        this._durationRecorder = durationRecorder;
        this._tafficRecorder = tafficRecorder;
        // this._asyncContext = asyncContext;
    }

    @Override
    public Observable<Interact> interact(final HttpClient client) {
        return this._getTracer.map(tracer -> interact(client, tracer));
    }

    public Interact interact(final HttpClient client, final Tracer tracer) {
        final InitiatorBuilder _initiatorBuilder = client.initiator();
        final AtomicBoolean _isSSLEnabled = new AtomicBoolean(false);
        final AtomicReference<Observable<Object>> _obsreqRef = new AtomicReference<>(
                MessageUtil.fullRequestWithoutBody(HttpVersion.HTTP_1_1, HttpMethod.GET));
        final AtomicReference<Action1<Object>> _onsendingRef = new AtomicReference<>();

        final AtomicReference<URI> _uriRef = new AtomicReference<>();
        final AtomicReference<String> _nameRef = new AtomicReference<>();

        final Span span = tracer.buildSpan("interact")
                .ignoreActiveSpan()
                .withTag(Tags.COMPONENT.getKey(), "jocean-http")
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                .withTag(Tags.HTTP_METHOD.getKey(), HttpMethod.GET.name())
                .asChildOf(this._span)
                .start();

        return new Interact() {
            private void updateObsRequest(final Action1<Object> action) {
                _obsreqRef.set(_obsreqRef.get().doOnNext(action));
            }

            private void extractUriWithHost(final Object...reqbeans) {
                if (null == _uriRef.get()) {
                    for (final Object bean : reqbeans) {
                        try {
                            final Path path = bean.getClass().getAnnotation(Path.class);
                            if (null != path) {
                                final URI uri = new URI(path.value());
                                if (null != uri.getHost()) {
                                    uri(path.value());
                                    return;
                                }
                            }
                        } catch (final Exception e) {
                            LOG.warn("exception when extract uri from bean {}, detail: {}",
                                    bean, ExceptionUtils.exception2detail(e));
                        }
                    }
                }
            }

            private void checkAddr() {
                if (null == _uriRef.get()) {
                    throw new RuntimeException("remote address not set.");
                }
            }

            private InitiatorBuilder addSSLFeatureIfNeed(final InitiatorBuilder builder) {
                if (_isSSLEnabled.get()) {
                    return builder;
                } else if ("https".equals(_uriRef.get().getScheme())) {
                    return builder.feature(F_SSL);
                } else {
                    return builder;
                }
            }

            @Override
            public Interact name(final String name) {
                _nameRef.set(name);
                return this;
            }

            @Override
            public Interact method(final HttpMethod method) {
                updateObsRequest(MessageUtil.setMethod(method));
                span.setTag(Tags.HTTP_METHOD.getKey(), method.name());
                return this;
            }

            @Override
            public Interact uri(final String uriAsString) {
                try {
                    final URI uri = new URI(uriAsString);
                    _uriRef.set(uri);
                    _initiatorBuilder.remoteAddress(MessageUtil.uri2addr(uri));
                    updateObsRequest(MessageUtil.setHost(uri));

                    span.setTag(Tags.PEER_HOST_IPV4.getKey(), uri.getHost());
                    span.setTag(Tags.PEER_HOSTNAME.getKey(), uri.getHost());
                    span.setTag(Tags.PEER_PORT.getKey(), uri.getPort());

                } catch (final URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                return this;
            }

            @Override
            public Interact path(final String path) {
                updateObsRequest(MessageUtil.setPath(path));
                return this;
            }

            @Override
            public Interact paramAsQuery(final String name, final String value) {
                updateObsRequest(MessageUtil.addQueryParam(name, value));
                return this;
            }

            @Override
            public Interact reqbean(final Object... reqbeans) {
                updateObsRequest(MessageUtil.toRequest(reqbeans));
                extractUriWithHost(reqbeans);
                return this;
            }

            @Override
            public Interact body(final Observable<? extends MessageBody> body) {
                _obsreqRef.set(_obsreqRef.get().compose(MessageUtil.addBody(body)));
                return this;
            }

            @Override
            public Interact body(final Object bean, final ContentEncoder contentEncoder) {
                _obsreqRef.set(_obsreqRef.get().compose(addBody(tobody(bean, contentEncoder, span))));
                return this;
            }

            @Override
            public Interact onrequest(final Action1<Object> action) {
                updateObsRequest(action);
                return this;
            }

            @Override
            public Interact onsending(final Action1<Object> action) {
                final Action1<Object> prev = _onsendingRef.get();
                if (null != prev) {
                    _onsendingRef.set(obj -> {
                            try {
                                prev.call(obj);
                            } catch (final Exception e) {
                                LOG.warn("exception when invoke prev Action1:{}, detail:{}", prev, ExceptionUtils.exception2detail(e));
                            }
                            try {
                                action.call(obj);
                            } catch (final Exception e) {
                                LOG.warn("exception when invoke next Action1:{}, detail:{}", action, ExceptionUtils.exception2detail(e));
                            }
                        });
                }
                else {
                    _onsendingRef.set(action);
                }
                return this;
            }

            @Override
            public Interact feature(final Feature... features) {
                _initiatorBuilder.feature(features);
                if (isSSLEnabled(features)) {
                    _isSSLEnabled.set(true);
                }
                return this;
            }

            private Observable<FullMessage<HttpResponse>> defineInteraction(final HttpInitiator initiator) {
                return initiator.defineInteraction(_obsreqRef.get())
                        .doOnNext(TraceUtil.hookhttpresp(span))
                        .compose(TraceUtil.logbody(span, "http.resp", 1024));
            }

            @Override
            public <T> Observable<T> responseAs(final ContentDecoder decoder, final Class<T> type) {
                checkAddr();
                return addSSLFeatureIfNeed(_initiatorBuilder).build()
                        .flatMap(initiator -> {
                            if ( null != _haltable) {
                                _haltable.doOnHalt(initiator.closer());
                            }

                            hookOnSending(initiator);
                            checkAndFixContentLength(initiator);

                            final AtomicReference<String> operationRef = new AtomicReference<>();
                            final AtomicReference<Long> startRef = new AtomicReference<Long>();

                            TraceUtil.logoutmsg(initiator.writeCtrl(), span, "http.req", 1024);
                            traceAndInjectRequest(initiator.writeCtrl(), tracer, span, operationRef, startRef);

                            initiator.writeCtrl().sended().subscribe(sended -> DisposableWrapperUtil.dispose(sended));

                            final AtomicBoolean isSpanFinished = new AtomicBoolean(false);
                            return defineInteraction(initiator ).flatMap(MessageUtil.fullmsg2body())
                                        .compose(MessageUtil.body2bean(decoder, type))
//                                        .doOnNext(TraceUtil.setTag4bean(span, "resp.", "record.respbean.error"))
                                        .doOnNext(bean -> span.log(Collections.singletonMap("http.resp.bean", bean)))
                                        .doOnTerminate(() -> {
                                            if (isSpanFinished.compareAndSet(false, true)) {
                                                span.finish();
                                                recordDuration(operationRef.get(), System.currentTimeMillis() - startRef.get().longValue());
                                                recordTraffic(operationRef.get(), initiator.traffic().inboundBytes(), initiator.traffic().outboundBytes());
                                                LOG.debug("call span {} finish by doOnTerminate", span);
                                            }
                                        })
                                        .doOnUnsubscribe(() -> {
                                            if (isSpanFinished.compareAndSet(false, true)) {
                                                span.finish();
                                                recordDuration(operationRef.get(), System.currentTimeMillis() - startRef.get().longValue());
                                                recordTraffic(operationRef.get(), initiator.traffic().inboundBytes(), initiator.traffic().outboundBytes());
                                                LOG.debug("call span {} finish by doOnUnsubscribe", span);
                                            }
                                        })
                                        .observeOn(_scheduler, true);
                        }
                    );
            }

            @Override
            public <T> Observable<T> responseAs(final Class<T> type) {
                return responseAs(null, type);
            }

            @Override
            public Observable<FullMessage<HttpResponse>> response() {
                checkAddr();
                return addSSLFeatureIfNeed(_initiatorBuilder).build()
                        .flatMap(initiator -> {
                            if ( null != _haltable) {
                                _haltable.doOnHalt(initiator.closer());
                            }

                            hookOnSending(initiator);
                            checkAndFixContentLength(initiator);


                            final AtomicReference<String> operationRef = new AtomicReference<>("(unknown)");
                            final AtomicReference<Long> startRef = new AtomicReference<Long>(System.currentTimeMillis());

                            TraceUtil.logoutmsg(initiator.writeCtrl(), span, "http.req", 1024);
                            traceAndInjectRequest(initiator.writeCtrl(), tracer, span, operationRef, startRef);
                            initiator.writeCtrl().sended().subscribe(sended -> DisposableWrapperUtil.dispose(sended));

                            final AtomicBoolean isSpanFinished = new AtomicBoolean(false);
                            return defineInteraction(initiator).doOnTerminate(() -> {
                                    if (isSpanFinished.compareAndSet(false, true)) {
                                        if (null != _nameRef.get()) {
                                            operationRef.set(_nameRef.get());
                                        }
                                        span.finish();
                                        recordDuration(operationRef.get(), System.currentTimeMillis() - startRef.get().longValue());
                                        recordTraffic(operationRef.get(), initiator.traffic().inboundBytes(), initiator.traffic().outboundBytes());

                                        LOG.info("call span {} finish by doOnTerminate", span);
                                    }
                                })
                                .doOnUnsubscribe(() -> {
                                    if (isSpanFinished.compareAndSet(false, true)) {
                                        if (null != _nameRef.get()) {
                                            operationRef.set(_nameRef.get());
                                        }
                                        span.finish();
                                        recordDuration(operationRef.get(), System.currentTimeMillis() - startRef.get().longValue());
                                        recordTraffic(operationRef.get(), initiator.traffic().inboundBytes(), initiator.traffic().outboundBytes());
                                        LOG.info("call span {} finish by doOnUnsubscribe", span);
                                    }
                                });
                        }
                    );
            }

            private void hookOnSending(final HttpInitiator initiator) {
                if (null != _onsendingRef.get()) {
                    initiator.writeCtrl().sending().subscribe(_onsendingRef.get());
                }
            }
        };
    }

    private void recordTraffic(final String operation, final long inboundBytes, final long outboundBytes) {
        this._tafficRecorder.record(inboundBytes, outboundBytes, "operation", operation);
    }

    private void recordDuration(final String operation, final long duration) {
        this._durationRecorder.record(duration, TimeUnit.MILLISECONDS, "operation", operation);
    }

    private Observable<? extends MessageBody> tobody(final Object bean, final ContentEncoder contentEncoder, final Span span) {
        return Observable.defer(() -> {
//            TraceUtil.setTag4bean(bean, span, "req.bd.", "record.reqbean.error");
            span.log(Collections.singletonMap("http.req.bean", bean));

            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout =
                    new BufsOutputStream<>(MessageUtil.pooledAllocator(this._haltable, 8192), dwb->dwb.unwrap());
            final Iterable<? extends DisposableWrapper<? extends ByteBuf>> dwbs = MessageUtil.out2dwbs(bufout,
                    out -> contentEncoder.encoder().call(bean, out));

            return Observable.just((MessageBody)new MessageBody() {
                @Override
                public HttpHeaders headers() {
                    return EmptyHttpHeaders.INSTANCE;
                }
                @Override
                public String contentType() {
                    return contentEncoder.contentType();
                }
                @Override
                public int contentLength() {
                    return -1;
                }
                @Override
                public Observable<? extends ByteBufSlice> content() {
                    return Observable.just(new ByteBufSlice() {
                        @Override
                        public void step() {}

                        @Override
                        public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                            return dwbs;
                        }});
                }});
        });
    }

    private void traceAndInjectRequest(final WriteCtrl writeCtrl, final Tracer tracer, final Span span,
            final AtomicReference<String> operationRef, final AtomicReference<Long> startRef) {
        writeCtrl.sending().subscribe(obj -> {
            if (obj instanceof HttpRequest) {
                final HttpRequest req = (HttpRequest) obj;

                final QueryStringDecoder decoder = new QueryStringDecoder(req.uri());

                span.setOperationName(decoder.path());
                operationRef.set(decoder.path());
                startRef.set(System.currentTimeMillis());

                for (final Map.Entry<String, List<String>> entry : decoder.parameters().entrySet()) {
                    final String value = entry.getValue().size() == 1 ? entry.getValue().get(0)
                            : (entry.getValue().size() > 1 ? entry.getValue().toString() : "(null)");
                    span.setTag("req.qs." + entry.getKey(), value);
                }

                TraceUtil.addTagNotNull(span, "http.host", req.headers().get(HttpHeaderNames.HOST));
                tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS, TraceUtil.message2textmap(req));
            }
        });
    }

    private static void checkAndFixContentLength(final HttpInitiator initiator) {
        initiator.writeCtrl().sending().subscribe(obj -> {
            if (obj instanceof HttpRequest) {
                final HttpRequest req = (HttpRequest)obj;
                if (!HttpUtil.isContentLengthSet(req) && !HttpUtil.isTransferEncodingChunked(req)) {
                    HttpUtil.setContentLength(req, 0);
                }
            }
        });
    }

    public static Transformer<Object, Object> addBody(final Observable<? extends MessageBody> obsbody) {
        return msg -> {
                return msg.concatMap(obj -> {
                        if (obj instanceof HttpMessage) {
                            final HttpMessage httpmsg = (HttpMessage)obj;
                            return obsbody.flatMap(body->body.content().compose(MessageUtil.AUTOSTEP2DWB)
                                    .toList().flatMap(dwbs -> {
                                int length = 0;
                                for (final DisposableWrapper<? extends ByteBuf> dwb : dwbs) {
                                    length +=dwb.unwrap().readableBytes();
                                }
                                httpmsg.headers().set(HttpHeaderNames.CONTENT_TYPE, body.contentType());
                                // set content-length
                                httpmsg.headers().set(HttpHeaderNames.CONTENT_LENGTH, length);
                                return Observable.concat(Observable.just(httpmsg), Observable.from(dwbs));
                            }));
                        } else {
                            return Observable.just(obj);
                        }
                    });
            };
    }

    private static boolean isSSLEnabled(final Feature... features) {
        for (final Feature f : features) {
            if (f instanceof Feature.ENABLE_SSL) {
                return true;
            }
        }
        return false;
    }

    private final Haltable _haltable;
    private final Span _span;
    private final Observable<Tracer> _getTracer;
    private final Scheduler _scheduler;
    // private final Context _asyncContext;
    private final DurationRecorder _durationRecorder;
    private final TrafficRecorder _tafficRecorder;
}
