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

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Subscriber;

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
                        trade.outbound(
                            _registrar.buildResource((HttpRequest)msg, trade),
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
    
    @Override
    public void setMBeanRegister(final MBeanRegister register) {
//        register.registerMBean("name=tradeProcessor", this);
    }

}
