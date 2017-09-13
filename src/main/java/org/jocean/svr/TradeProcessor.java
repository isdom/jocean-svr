/**
 * 
 */
package org.jocean.svr;

import org.jocean.http.WritePolicy;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.j2se.jmx.MBeanRegister;
import org.jocean.j2se.jmx.MBeanRegisterAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;

/**
 * @author isdom
 *
 */
public class TradeProcessor extends Subscriber<HttpTrade> 
    implements MBeanRegisterAware {

    private static final Logger LOG =
            LoggerFactory.getLogger(TradeProcessor.class);

    public TradeProcessor(
            final Registrar  registrar) {
        this._registrar = registrar;
    }
    
    public void setAutoCORS(final boolean autoCORS) {
        this._autoCORS = autoCORS;
    }

    @Override
    public void onCompleted() {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onError(final Throwable e) {
        LOG.warn("fatal error with {} restin.", ExceptionUtils.exception2detail(e));
    }

    @Override
    public void onNext(final HttpTrade trade) {
        trade.inbound().subscribe(
            buildInboundSubscriber(trade));
    }

    private Subscriber<HttpObject> buildInboundSubscriber(
            final HttpTrade trade) {
        return new Subscriber<HttpObject>() {
            @Override
            public void onCompleted() {
            }

            @Override
            public void onError(final Throwable e) {
                LOG.warn("SOURCE_CANCELED\nfor cause:[{}]", 
                    ExceptionUtils.exception2detail(e));
            }
            
            @Override
            public void onNext(final HttpObject msg) {
                if (msg instanceof HttpRequest) {
                    try {
                        final HttpRequest request = (HttpRequest)msg;
                        Observable<HttpObject> response = _registrar.buildResource(request, trade);
                        if (_autoCORS) {
                            final String origin = request.headers().get(HttpHeaderNames.ORIGIN);
                            if (null != origin) {
                                response = response.doOnNext(new Action1<HttpObject>() {
                                    @Override
                                    public void call(final HttpObject hobj) {
                                        if (hobj instanceof HttpResponse) {
                                            ((HttpResponse)hobj).headers().set(
                                                HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin); 
                                            ((HttpResponse)hobj).headers().set(
                                                HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, true);
                                        }
                                    }});
                            }
                        }
                        trade.outbound(
                            response,
                            new WritePolicy() {
                                @Override
                                public void applyTo(final Outboundable outboundable) {
                                    outboundable.setFlushPerWrite(false);
                                }}
                            );
                    } catch (Exception e) {
                        LOG.warn("exception when buildResource, detail:{}",
                                ExceptionUtils.exception2detail(e));
                    }
                }
            }
        };
    }

    private final Registrar _registrar;
    
    private boolean _autoCORS = false;
    
    @Override
    public void setMBeanRegister(final MBeanRegister register) {
//        register.registerMBean("name=tradeProcessor", this);
    }

}
