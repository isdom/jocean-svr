package org.jocean.svr;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.Path;

import org.jocean.http.ContentEncoder;
import org.jocean.http.Feature;
import org.jocean.http.Interaction;
import org.jocean.http.InteractionBuilder;
import org.jocean.http.MessageBody;
import org.jocean.http.MessageUtil;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.HttpClient.InitiatorBuilder;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Terminable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContextBuilder;
import rx.Observable;
import rx.functions.Action1;

public class InteractionBuilderImpl implements InteractionBuilder {
    
    private static final Logger LOG
        = LoggerFactory.getLogger(InteractionBuilderImpl.class);
    
    private void updateObsRequest(final Action1<Object> action) {
        _obsreqRef.set(_obsreqRef.get().doOnNext(action));
    }

    public InteractionBuilderImpl(final Terminable terminable) {
        this._terminable = terminable;
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

    private void checkAndSetAddr(final InitiatorBuilder initiatorBuilder) {
        if (null == _uriRef.get()) {
            throw new RuntimeException("remote address not set.");
        }
        initiatorBuilder.remoteAddress(MessageUtil.uri2addr(_uriRef.get()));
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
    public InteractionBuilder method(final HttpMethod method) {
        updateObsRequest(MessageUtil.setMethod(method));
        return this;
    }

    @Override
    public InteractionBuilder uri(final String uriAsString) {
        try {
            final URI uri = new URI(uriAsString);
            _uriRef.set(uri);
            updateObsRequest(MessageUtil.setHost(uri));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    @Override
    public InteractionBuilder path(final String path) {
        updateObsRequest(MessageUtil.setPath(path));
        return this;
    }

    @Override
    public InteractionBuilder paramAsQuery(final String name, final String value) {
        _nvs.add(name);
        _nvs.add(value);
        return this;
    }

    @Override
    public InteractionBuilder reqbean(final Object... reqbeans) {
        updateObsRequest(MessageUtil.toRequest(reqbeans));
        extractUriWithHost(reqbeans);
        return this;
    }

    @Override
    public InteractionBuilder body(final Observable<? extends MessageBody> body) {
        _obsreqRef.set(_obsreqRef.get().compose(MessageUtil.addBody(body)));
        return this;
    }
    
//    @Override
//    public InteractionBuilder disposeBodyOnTerminate(final boolean doDispose) {
//        _doDisposeBody.set(doDispose);
//        return this;
//    }
    
    @Override
    public InteractionBuilder onrequest(final Action1<Object> action) {
        updateObsRequest(action);
        return this;
    }
    
    @Override
    public InteractionBuilder feature(final Feature... features) {
        _features.addAll(Arrays.asList(features));
        if (isSSLEnabled(features)) {
            _isSSLEnabled.set(true);
        }
        return this;
    }

    private boolean isSSLEnabled(final Feature... features) {
        for (Feature f : features) {
            if (f instanceof Feature.ENABLE_SSL) {
                return true;
            }
        }
        return false;
    }

    private Observable<? extends Object> hookDisposeBody(final Observable<Object> obsreq, final HttpInitiator initiator) {
        return _doDisposeBody.get() ? obsreq.doOnNext(DisposableWrapperUtil.disposeOnForAny(initiator))
                : obsreq;
    }
    
    private Observable<? extends DisposableWrapper<HttpObject>> defineInteraction(final HttpInitiator initiator) {
        return initiator.defineInteraction(hookDisposeBody(_obsreqRef.get(), initiator));
    }
    
    @Override
    public Observable<? extends Interaction> execution() {
        if (null == _clientRef.get()) {
            throw new RuntimeException("http client not set.");
        }
        final InitiatorBuilder initiatorBuilder = _clientRef.get().initiator();
        checkAndSetAddr(initiatorBuilder);
        addQueryParams();
        initiatorBuilder.feature(_features.toArray(new Feature[0]));
        return addSSLFeatureIfNeed(initiatorBuilder).build()
                .<Interaction>map(initiator -> {
                        if ( null != _terminable) {
                            _terminable.doOnTerminate(initiator.closer());
                        }
                        final Observable<? extends DisposableWrapper<HttpObject>> interaction = 
                                defineInteraction(initiator);
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
    
    @Override
    public InteractionBuilder client(final HttpClient client) {
        _clientRef.set(client);
        return this;
    }

    @Override
    public InteractionBuilder body(final Object bean, final ContentEncoder contentEncoder) {
        throw new RuntimeException("NOT IMPL");
    }
    
    private final Terminable _terminable;
    final AtomicReference<HttpClient> _clientRef = new AtomicReference<>();
    final AtomicBoolean _isSSLEnabled = new AtomicBoolean(false);
    final AtomicBoolean _doDisposeBody = new AtomicBoolean(true);
    final AtomicReference<Observable<Object>> _obsreqRef = new AtomicReference<>(
            MessageUtil.fullRequestWithoutBody(HttpVersion.HTTP_1_1, HttpMethod.GET));
    final List<String> _nvs = new ArrayList<>();
    final AtomicReference<URI> _uriRef = new AtomicReference<>();
    final List<Feature> _features = new ArrayList<>();

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
}
