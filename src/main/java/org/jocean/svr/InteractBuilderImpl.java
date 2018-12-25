package org.jocean.svr;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.Path;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.ContentEncoder;
import org.jocean.http.Feature;
import org.jocean.http.FullMessage;
import org.jocean.http.Interact;
import org.jocean.http.InteractBuilder;
import org.jocean.http.Interaction;
import org.jocean.http.MessageBody;
import org.jocean.http.MessageUtil;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.HttpClient.InitiatorBuilder;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Terminable;
import org.jocean.netty.util.BufsInputStream;
import org.jocean.netty.util.BufsOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslContextBuilder;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;

public class InteractBuilderImpl implements InteractBuilder {

    private static final Logger LOG
        = LoggerFactory.getLogger(InteractBuilderImpl.class);

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

    public InteractBuilderImpl(final Terminable terminable, final Span span, final Observable<Tracer> getTracer) {
        this._terminable = terminable;
        this._span = span;
        this._getTracer = getTracer;
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

        final List<String> _nvs = new ArrayList<>();
        final AtomicReference<URI> _uriRef = new AtomicReference<>();
        final Span span = tracer.buildSpan("interact")
                .withTag(Tags.COMPONENT.getKey(), "jocean-http")
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                .withTag(Tags.HTTP_METHOD.getKey(), HttpMethod.GET.name())
                .asChildOf(this._span)
                .start();

        return new Interact() {
            private void updateObsRequest(final Action1<Object> action) {
                _obsreqRef.set(_obsreqRef.get().doOnNext(action));
            }

            private void addQueryParams() {
                if (!_nvs.isEmpty()) {
                    updateObsRequest(MessageUtil.addQueryParam(_nvs.toArray(new String[0])));
                }
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
                _nvs.add(name);
                _nvs.add(value);
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
            public Interact feature(final Feature... features) {
                _initiatorBuilder.feature(features);
                if (isSSLEnabled(features)) {
                    _isSSLEnabled.set(true);
                }
                return this;
            }

            private Observable<FullMessage<HttpResponse>> defineInteraction(final HttpInitiator initiator) {
                final StringBuilder bodysb4span = new StringBuilder();
                final AtomicInteger bodysize4span = new AtomicInteger(0);

                return initiator.defineInteraction(_obsreqRef.get())
                        .map(fullresp -> {
                            final int statusCode = fullresp.message().status().code();
                            span.setTag(Tags.HTTP_STATUS.getKey(), statusCode);
                            if (statusCode >= 300 && statusCode < 400) {
                                addTagNotNull(span, "http.location", fullresp.message().headers().get(HttpHeaderNames.LOCATION));
                            }
                            if (statusCode >= 400) {
                                span.setTag(Tags.ERROR.getKey(), true);
                            }
                            final int MAX_SIZE = 256;
                            final BufsInputStream<ByteBuf> bufsin = new BufsInputStream<>(buf -> buf, buf -> {});

                            bufsin.markEOS();

                            return (FullMessage<HttpResponse>)new FullMessage<HttpResponse>() {
                                @Override
                                public HttpResponse message() {
                                    return fullresp.message();
                                }
                                @Override
                                public Observable<? extends MessageBody> body() {
                                    return fullresp.body().map(body -> {
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
                                                    if (bodysize4span.get() < MAX_SIZE) {
                                                        final Iterator<? extends DisposableWrapper<? extends ByteBuf>> iter =
                                                                bbs.element().iterator();
                                                        for (;;) {
                                                            if (iter.hasNext()) {
                                                                final ByteBuf buf = iter.next().unwrap();
                                                                if (buf.readableBytes() > 0 && bodysize4span.get() < MAX_SIZE) {
                                                                    final int length = Math.min(MAX_SIZE - bodysize4span.get(), buf.readableBytes());
                                                                    bufsin.appendBuf(buf.readSlice(length));
                                                                    bodysize4span.addAndGet(length);
                                                                }
                                                                if (bodysize4span.get() >= MAX_SIZE) {
                                                                    break;
                                                                }
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
                                span.setTag("http.resp.raw", bodysb4span.toString());
                            }
                            span.finish();
                        });
            }

            @Override
            public Observable<? extends Interaction> execution() {
                checkAddr();
                addQueryParams();
                return addSSLFeatureIfNeed(_initiatorBuilder).build()
                        .<Interaction>map(initiator -> {
                            if ( null != _terminable) {
                                _terminable.doOnTerminate(initiator.closer());
                            }

                            initiator.writeCtrl().sending().subscribe(obj -> {
                                if (obj instanceof HttpRequest) {
                                    final HttpRequest req = (HttpRequest)obj;

                                    final QueryStringDecoder decoder = new QueryStringDecoder(req.uri());

                                    span.setOperationName(decoder.path());
                                    for (final Map.Entry<String, List<String>> entry : decoder.parameters().entrySet()) {
                                        final String value = entry.getValue().size() == 1 ? entry.getValue().get(0)
                                                : (entry.getValue().size() > 1  ? entry.getValue().toString() : "(null)");
                                        span.setTag("req." + entry.getKey(), value);
                                    }

                                    addTagNotNull(span, "http.host", req.headers().get(HttpHeaderNames.HOST));
                                    tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS, message2textmap(req));
                                }
                            });

                            final Observable<FullMessage<HttpResponse>> interaction = defineInteraction(initiator);
                            return new Interaction() {
                                @Override
                                public HttpInitiator initiator() {
                                    return initiator;
                                }

                                @Override
                                public Observable<? extends FullMessage<HttpResponse>> execute() {
                                    return interaction;
                                }};
                        }
                    );
            }
        };
    }

    // TODO move to util class
    private static void addTagNotNull(final Span span, final String tag, final String value) {
        if (null != value) {
            span.setTag(tag, value);
        }
    }

    // TODO move to util class
    private static TextMap message2textmap(final HttpMessage message) {
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

    private Observable<? extends MessageBody> tobody(final Object bean, final ContentEncoder contentEncoder, final Span span) {
        return Observable.defer(() -> {
            final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout =
                    new BufsOutputStream<>(MessageUtil.pooledAllocator(this._terminable, 8192), dwb->dwb.unwrap());
            final Iterable<? extends DisposableWrapper<? extends ByteBuf>> dwbs = MessageUtil.out2dwbs(bufout,
                    out -> contentEncoder.encoder((object, name, value) ->
                        span.setTag("req." + name, null != value ? value.toString() : "(null)"))
                    .call(bean, out));

            return Observable.just((MessageBody)new MessageBody() {
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

    private final Terminable _terminable;
    private final Span _span;
    private final Observable<Tracer> _getTracer;

}
