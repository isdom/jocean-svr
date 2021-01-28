package org.jocean.svr.tracing;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.FullMessage;
import org.jocean.http.MessageBody;
import org.jocean.http.WriteCtrl;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.netty.util.BufsInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.opentracing.Span;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;

public class TraceUtil {
    private static final Logger LOG = LoggerFactory.getLogger(TraceUtil.class);

    public static void hook4serversend(final WriteCtrl writeCtrl, final Span span) {
        writeCtrl.sending().subscribe(obj -> {
            if ( obj instanceof HttpResponse) {
                final HttpResponse resp = (HttpResponse)obj;
                final int statusCode = resp.status().code();
                span.setTag(Tags.HTTP_STATUS.getKey(), statusCode);
                if (statusCode >= 300 && statusCode < 400) {
                    final String location = resp.headers().get(HttpHeaderNames.LOCATION);
                    if (null != location) {
                        span.setTag("http.location", location);
                    }
                }
                if (statusCode >= 400) {
                    span.setTag(Tags.ERROR.getKey(), true);
                }
            }
        });
    }

    public static Action1<FullMessage<HttpResponse>> hookhttpresp(final Span span) {
        return fullresp -> {
            final int statusCode = fullresp.message().status().code();
            span.setTag(Tags.HTTP_STATUS.getKey(), statusCode);
            if (statusCode >= 300 && statusCode < 400) {
                addTagNotNull(span, "http.location", fullresp.message().headers().get(HttpHeaderNames.LOCATION));
            }
            if (statusCode >= 400) {
                span.setTag(Tags.ERROR.getKey(), true);
            }
        };
    }

    public static void addTagNotNull(final Span span, final String tag, final String value) {
        if (null != value) {
            span.setTag(tag, value);
        }
    }

    public static TextMap message2textmap(final HttpMessage message) {
        return new TextMap() {
            @Override
            public Iterator<Entry<String, String>> iterator() {
                return message.headers().iteratorAsString();
            }

            @Override
            public void put(final String key, final String value) {
                message.headers().set(key, value);
            }};
    }

//    public static void setTag4bean(final Object bean, final Span span, final String prefix, final String logexception) {
//        try {
//            final Map<String, String> map = BeanUtils.describe(bean);
//
//            for (final Map.Entry<String, String> entry : map.entrySet()) {
//                if (!entry.getKey().equals("class")) {
//                    span.setTag(prefix + entry.getKey(), entry.getValue());
//                }
//            }
//        } catch (final Exception e) {
//            span.log(Collections.singletonMap(logexception, ExceptionUtils.exception2detail(e)));
//            LOG.warn("exception when record bean, detail: {}", ExceptionUtils.exception2detail(e));
//        }
//    }
//
//    public static Action1<Object> setTag4bean(final Span span, final String prefix, final String logexception) {
//        return bean -> setTag4bean(bean, span, prefix, logexception);
//    }

    public static void logoutmsg(final WriteCtrl writeCtrl,
            final Span span, final String logprefix, final int maxLogSize) {
        final StringBuilder bodysb = new StringBuilder();
        final AtomicInteger loggedSize = new AtomicInteger(0);
        final BufsInputStream<ByteBuf> bufsin = new BufsInputStream<>(buf -> buf, buf -> {});
        bufsin.markEOS();

        writeCtrl.sending().subscribe(obj -> {
            final Object unwrap = DisposableWrapperUtil.unwrap(obj);
            if (unwrap instanceof HttpMessage) {
                final HttpMessage message = (HttpMessage)unwrap;
                final String contentType = message.headers().get(HttpHeaderNames.CONTENT_TYPE);
                if (contentType != null) {
                    span.setTag(logprefix + ".contenttype", contentType);
                }

                final int contentLength = HttpUtil.getContentLength(message, -1);
                if (contentLength != -1) {
                    span.setTag(logprefix + ".contentlength", contentLength);
                }
            } else if (unwrap instanceof ByteBufHolder) {
                logByteBuf(((ByteBufHolder)unwrap).content(), bodysb, bufsin, loggedSize, maxLogSize);
            } else if (unwrap instanceof ByteBuf) {
                logByteBuf((ByteBuf)unwrap, bodysb, bufsin, loggedSize, maxLogSize);
            }
        }, e -> {}, ()-> {
            if (loggedSize.get() > 0) {
                span.log(Collections.singletonMap(logprefix + ".body", bodysb.toString()));
            }
        });
    }

    private static void logByteBuf(final ByteBuf buf,
            final StringBuilder sb, final BufsInputStream<ByteBuf> bufsin,
            final AtomicInteger loggedSize, final int maxLogSize) {
        if (loggedSize.get() < maxLogSize && buf.readableBytes() > 0) {
            final int size = Math.min(maxLogSize - loggedSize.get(), buf.readableBytes());
            bufsin.appendBuf(buf.slice(0, size));
            loggedSize.addAndGet(size);
            try {
                sb.append(new String(ByteStreams.toByteArray(bufsin), Charsets.UTF_8));
            } catch (final Exception e) {}
        }
    }

    public static <MSG extends HttpMessage> Transformer<FullMessage<MSG>, FullMessage<MSG>> logbody(
            final Span span, final String logprefix, final int maxLogSize) {
        final StringBuilder bodysb4span = new StringBuilder();
        final AtomicInteger bodysize4span = new AtomicInteger(0);

        return new Transformer<FullMessage<MSG>, FullMessage<MSG>>() {
            @Override
            public Observable<FullMessage<MSG>> call(final Observable<FullMessage<MSG>> fullmsgs) {
                return fullmsgs.map(fullmsg -> {
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
                                span.setTag( logprefix + ".contenttype", body.contentType());
                                span.setTag( logprefix + ".contentlength", body.contentLength());
                                return (MessageBody)new MessageBody() {
                                    @Override
                                    public HttpHeaders headers() {
                                        return body.headers();
                                    }
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
                        span.log(Collections.singletonMap(logprefix + ".body", bodysb4span.toString()));
                    }
                });
            }};
    }
}
