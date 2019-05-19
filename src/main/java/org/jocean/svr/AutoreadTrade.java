package org.jocean.svr;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.http.TrafficCounter;
import org.jocean.http.WriteCtrl;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.rx.RxSubscribers;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import rx.Completable;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;

public class AutoreadTrade implements HttpTrade {

    public static HttpTrade enableAutoread(final HttpTrade trade) {
        if (trade instanceof AutoreadTrade) {
            return trade;
        } else {
            return new AutoreadTrade(trade);
        }
    }

    final HttpTrade _trade;
    final Observable<FullMessage<HttpRequest>> _autoreadInbound;

    private AutoreadTrade(final HttpTrade trade) {
        this._trade = trade;
        this._autoreadInbound =
                trade.inbound().<FullMessage<HttpRequest>>map(fullmsg -> {
                    final Observable<MessageBody> cachedBody = fullmsg.body().<MessageBody>map(body -> {
                        final Observable<? extends ByteBufSlice> cachedContent =
                                body.content().doOnNext(bbs -> bbs.step()).cache();
                        cachedContent.subscribe(RxSubscribers.ignoreNext(), RxSubscribers.ignoreError());
                        return new MessageBody() {
                            @Override
                            public HttpHeaders headers() {
                                return body.headers();
                            }                            @Override
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

        this._autoreadInbound.subscribe(RxSubscribers.ignoreNext(), RxSubscribers.ignoreError());
    }

    @Override
    public Action1<Action0> onEnd() {
        return _trade.onEnd();
    }

    @Override
    public Action1<Action1<HttpTrade>> onEndOf() {
        return _trade.onEndOf();
    }

    @Override
    public Action0 doOnEnd(final Action0 onTerminate) {
        return _trade.doOnEnd(onTerminate);
    }

    @Override
    public Action0 doOnEnd(final Action1<HttpTrade> onTerminate) {
        return _trade.doOnEnd(onTerminate);
    }

    @Override
    public Completable inboundCompleted() {
        return _trade.inboundCompleted();
    }

    @Override
    public Observable<FullMessage<HttpRequest>> inbound() {
        return this._autoreadInbound;
    }

    @Override
    public Subscription outbound(final Observable<? extends Object> message) {
        return _trade.outbound(message);
    }

    @Override
    public Object transport() {
        return _trade.transport();
    }

    @Override
    public Action0 closer() {
        return _trade.closer();
    }

    @Override
    public void close() {
        _trade.close();
    }

    @Override
    public TrafficCounter traffic() {
        return _trade.traffic();
    }

    @Override
    public boolean isActive() {
        return _trade.isActive();
    }

    @Override
    public WriteCtrl writeCtrl() {
        return _trade.writeCtrl();
    }

    @Override
    public Intraffic intraffic() {
        return _trade.intraffic();
    }

    @Override
    public String toString() {
        return new StringBuilder().append("(Autoread Enabled) ").append(_trade.toString()).toString();
    }
}