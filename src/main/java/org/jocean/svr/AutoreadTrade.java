package org.jocean.svr;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.http.TrafficCounter;
import org.jocean.http.WriteCtrl;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.rx.RxSubscribers;
import org.jocean.netty.util.BufsInputStream;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import rx.Completable;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Action2;

class AutoreadTrade implements HttpTrade {

    public static HttpTrade enableAutoread(final HttpTrade trade, final int maxLogSize, final StringBuilder logsb) {
        if (trade instanceof AutoreadTrade) {
            return trade;
        } else {
            return new AutoreadTrade(trade, maxLogSize, logsb);
        }
    }

    final HttpTrade _trade;
    final Observable<FullMessage<HttpRequest>> _autoreadInbound;

    private AutoreadTrade(final HttpTrade trade, final int maxLogSize, final StringBuilder logsb) {
        final AtomicInteger loggedSize = new AtomicInteger(0);
        final BufsInputStream<ByteBuf> bufsin = new BufsInputStream<>(buf -> buf, buf -> {});
        bufsin.markEOS();

        this._trade = trade;
        this._autoreadInbound =
                trade.inbound().<FullMessage<HttpRequest>>map(fullmsg -> {
                    final Observable<MessageBody> cachedBody = fullmsg.body().<MessageBody>map(body -> {
                        final Observable<? extends ByteBufSlice> cachedContent =
                                body.content()/*.doOnNext(bbs -> bbs.step())*/.cache();
                        cachedContent.subscribe(bbs -> {
                            try {
                                for (final DisposableWrapper<? extends ByteBuf> dwb : bbs.element()) {
                                    final ByteBuf buf = dwb.unwrap();
                                    if (loggedSize.get() < maxLogSize && buf.readableBytes() > 0) {
                                        final int size = Math.min(maxLogSize - loggedSize.get(), buf.readableBytes());
                                        bufsin.appendBuf(buf.slice(0, size));
                                        loggedSize.addAndGet(size);
                                    }
                                }
                                if (bufsin.available() > 0) {
                                    try {
                                        logsb.append(new String(ByteStreams.toByteArray(bufsin), Charsets.UTF_8));
                                    } catch (final Exception e) {}
                                }
                            } finally {
                                bbs.step();
                            }
                        }, e -> trade.log(Collections.singletonMap("autoread.error", e)));
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
    public Action1<Action0> onHalt() {
        return _trade.onHalt();
    }

    @Override
    public Action1<Action1<HttpTrade>> onHaltOf() {
        return _trade.onHaltOf();
    }

    @Override
    public Action0 doOnHalt(final Action0 onhalt) {
        return _trade.doOnHalt(onhalt);
    }

    @Override
    public Action0 doOnHalt(final Action1<HttpTrade> onhalt) {
        return _trade.doOnHalt(onhalt);
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

    @Override
    public long startTimeMillis() {
        return _trade.startTimeMillis();
    }

    @Override
    public void log(final Map<String, ?> fields) {
        _trade.log(fields);
    }

    @Override
    public void visitlogs(final Action2<Long, Map<String, ?>> logvisitor) {
        _trade.visitlogs(logvisitor);
    }
}
