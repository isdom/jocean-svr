/**
 *
 */
package org.jocean.svr;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.http.TrafficCounter;
import org.jocean.http.WriteCtrl;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.jmx.MBeanRegister;
import org.jocean.idiom.jmx.MBeanRegisterAware;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import rx.Completable;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;

/**
 * @author isdom
 *
 */
public class TradeProcessor extends Subscriber<HttpTrade>
    implements MBeanRegisterAware {

    private static final Logger LOG =
            LoggerFactory.getLogger(TradeProcessor.class);

    public TradeProcessor(final Registrar registrar) {
        this._registrar = registrar;
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
        trade.inbound().subscribe(fullreq -> {
            if ( this._maxContentLengthForAutoread <= 0) {
                LOG.debug("disable autoread full request, handle raw {}.", trade);
                handleTrade(fullreq, trade);
            } else {
                tryHandleTradeWithAutoread(fullreq, trade);
            }
        }, e -> LOG.warn("SOURCE_CANCELED\nfor cause:[{}]", ExceptionUtils.exception2detail(e)));
    }

    private void tryHandleTradeWithAutoread(final FullMessage<HttpRequest> fullreq, final HttpTrade trade) {
        if (HttpUtil.isTransferEncodingChunked(fullreq.message())) {
            // chunked
            // output log and using raw trade
            LOG.info("chunked request, handle raw {}.", trade);
            handleTrade(fullreq, trade);
        } else if (!HttpUtil.isContentLengthSet(fullreq.message())) {
            LOG.debug("content-length not set, handle raw {}.", trade);
            // not set content-length and not chunked
            handleTrade(fullreq, trade);
        } else {
            final long contentLength = HttpUtil.getContentLength(fullreq.message());
            if (contentLength <= this._maxContentLengthForAutoread) {
                LOG.debug("content-length is {} <= {}, enable autoread full request for {}.",
                        contentLength, this._maxContentLengthForAutoread, trade);
                // auto read all request
                handleTrade(fullreq, enableAutoread(trade));
            } else {
                // content-length > max content-length
                LOG.debug("content-length is {} > {}, handle raw {}.",
                        contentLength, this._maxContentLengthForAutoread, trade);
                handleTrade(fullreq, trade);
            }
        }
    }

    private HttpTrade enableAutoread(final HttpTrade trade) {
        final Observable<FullMessage<HttpRequest>> autoreadInbound =
                trade.inbound().<FullMessage<HttpRequest>>map(fullmsg -> {
                    final Observable<MessageBody> cachedBody = fullmsg.body().<MessageBody>map(body -> {
                        final Observable<? extends ByteBufSlice> cachedContent =
                                body.content().doOnNext(bbs -> bbs.step()).cache();
                        cachedContent.subscribe(RxSubscribers.ignoreNext(), RxSubscribers.ignoreError());
                        return new MessageBody() {
                            @Override
                            public String contentType() {
                                return body.contentType();
                            }
                            @Override
                            public int contentLength() {
                                return body.contentLength();
                            }
                            @Override
                            public Observable<? extends ByteBufSlice> content() {
                                return cachedContent;
                            }};
                    }).cache();
                    cachedBody.subscribe(RxSubscribers.ignoreNext(), RxSubscribers.ignoreError());
                    return new FullMessage<HttpRequest>() {
                        @Override
                        public HttpRequest message() {
                            return fullmsg.message();
                        }
                        @Override
                        public Observable<? extends MessageBody> body() {
                            return cachedBody;
                        }};
                }).cache();

        autoreadInbound.subscribe(RxSubscribers.ignoreNext(), RxSubscribers.ignoreError());

        return new HttpTrade() {

            @Override
            public Action1<Action0> onTerminate() {
                return trade.onTerminate();
            }

            @Override
            public Action1<Action1<HttpTrade>> onTerminateOf() {
                return trade.onTerminateOf();
            }

            @Override
            public Action0 doOnTerminate(final Action0 onTerminate) {
                return trade.doOnTerminate(onTerminate);
            }

            @Override
            public Action0 doOnTerminate(final Action1<HttpTrade> onTerminate) {
                return trade.doOnTerminate(onTerminate);
            }

            @Override
            public Completable inboundCompleted() {
                return trade.inboundCompleted();
            }

            @Override
            public Observable<FullMessage<HttpRequest>> inbound() {
                return autoreadInbound;
            }

            @Override
            public Subscription outbound(final Observable<? extends Object> message) {
                return trade.outbound(message);
            }

            @Override
            public Object transport() {
                return trade.transport();
            }

            @Override
            public Action0 closer() {
                return trade.closer();
            }

            @Override
            public void close() {
                trade.close();
            }

            @Override
            public TrafficCounter traffic() {
                return trade.traffic();
            }

            @Override
            public boolean isActive() {
                return trade.isActive();
            }

            @Override
            public WriteCtrl writeCtrl() {
                return trade.writeCtrl();
            }

            @Override
            public Intraffic intraffic() {
                return trade.intraffic();
            }

            @Override
            public String toString() {
                return new StringBuilder().append("(Autoread Enabled) ").append(trade.toString()).toString();
            }};
    }

    private void handleTrade(final FullMessage<HttpRequest> fullreq, final HttpTrade trade) {
        try {
            final Observable<? extends Object> outbound = this._registrar.buildResource(fullreq.message(), trade);
            trade.outbound(outbound.doOnNext(DisposableWrapperUtil.disposeOnForAny(trade)));
        } catch (final Exception e) {
            LOG.warn("exception when buildResource, detail:{}",
                    ExceptionUtils.exception2detail(e));
        }
    }

    private final Registrar _registrar;

    @Value("${autoread.maxContentLength}")
    public void setMaxContentLengthForAutoread(final int length) {
        this._maxContentLengthForAutoread = length;
    }

    private int _maxContentLengthForAutoread = 8192;

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
//        register.registerMBean("name=tradeProcessor", this);
    }

}
