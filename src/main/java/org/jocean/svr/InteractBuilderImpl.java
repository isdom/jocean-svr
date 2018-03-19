package org.jocean.svr;

import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.Path;

import org.jocean.http.ContentEncoder;
import org.jocean.http.Feature;
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
import org.jocean.netty.util.BufsOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContextBuilder;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func0;

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
        } catch (Exception e) {
            LOG.error("exception init default ssl feature, detail: {}", ExceptionUtils.exception2detail(e));
            return null;
        }
    }
    
    public InteractBuilderImpl(final Terminable terminable) {
        this._terminable = terminable;
    }
    
    @Override
    public Interact interact(final HttpClient client) {
        final InitiatorBuilder _initiatorBuilder = client.initiator();
        final AtomicBoolean _isSSLEnabled = new AtomicBoolean(false);
        final AtomicReference<Observable<Object>> _obsreqRef = new AtomicReference<>(
                MessageUtil.fullRequestWithoutBody(HttpVersion.HTTP_1_1, HttpMethod.GET));
        
        final List<String> _nvs = new ArrayList<>();
        final AtomicReference<URI> _uriRef = new AtomicReference<>();
        
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
                    for (Object bean : reqbeans) {
                        try {
                            final Path path = bean.getClass().getAnnotation(Path.class);
                            if (null != path) {
                                final URI uri = new URI(path.value());
                                if (null != uri.getHost()) {
                                    uri(path.value());
                                    return;
                                }
                            }
                        } catch (Exception e) {
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
                return this;
            }

            @Override
            public Interact uri(final String uriAsString) {
                try {
                    final URI uri = new URI(uriAsString);
                    _uriRef.set(uri);
                    _initiatorBuilder.remoteAddress(MessageUtil.uri2addr(uri));
                    updateObsRequest(MessageUtil.setHost(uri));
                } catch (URISyntaxException e) {
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
                _obsreqRef.set(_obsreqRef.get().compose(addBody(tobody(bean, contentEncoder))));
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

            private Observable<? extends DisposableWrapper<HttpObject>> defineInteraction(final HttpInitiator initiator) {
                return initiator.defineInteraction(_obsreqRef.get());
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
                            final Observable<? extends DisposableWrapper<HttpObject>> interaction = defineInteraction(initiator);
                            return new Interaction() {
                                @Override
                                public HttpInitiator initiator() {
                                    return initiator;
                                }
    
                                @Override
                                public Observable<? extends DisposableWrapper<HttpObject>> execute() {
                                    return interaction;
                                }};
                        }
                    );
            }
        };
    }
    
    private Observable<? extends MessageBody> tobody(final Object bean, final ContentEncoder contentEncoder) {
        final Func0<BufsOutputStream<DisposableWrapper<ByteBuf>>> creator = 
                ()->new BufsOutputStream<>(MessageUtil.pooledAllocator(this._terminable, 8192), dwb->dwb.unwrap());
        final Action1<OutputStream> fillout = (out)->contentEncoder.encoder().call(bean, out);
        return Observable.just(new MessageBody() {
            @Override
            public String contentType() {
                return contentEncoder.contentType();
            }
            @Override
            public int contentLength() {
                return -1;
            }
            @Override
            public Observable<? extends DisposableWrapper<ByteBuf>> content() {
                return MessageUtil.fromBufout(creator, fillout);
            }});
    }

    public static Transformer<Object, Object> addBody(final Observable<? extends MessageBody> obsbody) {
        return new Transformer<Object, Object>() {
            @Override
            public Observable<Object> call(final Observable<Object> msg) {
                return msg.concatMap(obj -> {
                        if (obj instanceof HttpMessage) {
                            final HttpMessage httpmsg = (HttpMessage)obj;
                            return obsbody.flatMap(body->body.content().toList().flatMap(dwbs -> {
                                int length = 0;
                                for (DisposableWrapper<ByteBuf> dwb : dwbs) {
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
            }
        };
    }
    
    private static boolean isSSLEnabled(final Feature... features) {
        for (Feature f : features) {
            if (f instanceof Feature.ENABLE_SSL) {
                return true;
            }
        }
        return false;
    }

    private final Terminable _terminable;
}
