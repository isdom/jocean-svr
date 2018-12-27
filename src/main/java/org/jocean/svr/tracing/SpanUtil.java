package org.jocean.svr.tracing;

import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.netty.util.BufsInputStream;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMessage;
import io.opentracing.Span;
import rx.Observable;
import rx.Observable.Transformer;

public class SpanUtil {
    public static <MSG extends HttpMessage> Transformer<FullMessage<MSG>, FullMessage<MSG>> logbody(
            final Span span, final String logname, final int maxLogSize) {
        final StringBuilder bodysb4span = new StringBuilder();
        final AtomicInteger bodysize4span = new AtomicInteger(0);

        return fullmsgs -> fullmsgs.map(fullmsg -> {
            final BufsInputStream<ByteBuf> bufsin = new BufsInputStream<>(buf -> buf, buf -> {});

            bufsin.markEOS();

            return (FullMessage<MSG>)new FullMessage<MSG>() {
                @Override
                public MSG message() {
                    return fullmsg.message();
                }
                @Override
                public Observable<? extends MessageBody> body() {
                    return fullmsg.body().map(body -> {
                        span.setTag("http.contenttype", body.contentType());
                        span.setTag("http.contentlength", body.contentLength());
                        return (MessageBody)new MessageBody() {
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
                                return body.content().doOnNext(bbs -> {
                                    if (bodysize4span.get() < maxLogSize) {
                                        final Iterator<? extends DisposableWrapper<? extends ByteBuf>> iter =
                                                bbs.element().iterator();
                                        for (;iter.hasNext();) {
                                            final ByteBuf buf = iter.next().unwrap();
                                            if (buf.readableBytes() > 0 && bodysize4span.get() < maxLogSize) {
                                                final int length = Math.min(maxLogSize - bodysize4span.get(),
                                                        buf.readableBytes());
                                                bufsin.appendBuf(buf.slice(0, length));
                                                bodysize4span.addAndGet(length);
                                            }
                                            if (bodysize4span.get() >= maxLogSize) {
                                                break;
                                            }
                                        }
                                        try {
                                            bodysb4span.append(new String(ByteStreams.toByteArray(bufsin), Charsets.UTF_8));
                                        } catch (final Exception e) {}
                                    }
                                });
                            }};
                    });
                }};
        })
        .doOnTerminate(() -> {
            if (bodysize4span.get() > 0) {
                span.log(Collections.singletonMap(logname, bodysb4span.toString()));
            }
        });
    }
}
