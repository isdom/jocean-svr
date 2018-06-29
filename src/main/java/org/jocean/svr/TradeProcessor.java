/**
 *
 */
package org.jocean.svr;

import org.jocean.http.MessageUtil;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.jmx.MBeanRegister;
import org.jocean.idiom.jmx.MBeanRegisterAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;
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
        trade.inbound()
            .compose(MessageUtil.dwhWithAutoread())
            .map(DisposableWrapperUtil.unwrap()).subscribe(
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
                        final Observable<? extends Object> outbound = _registrar.buildResource((HttpRequest)msg, trade);
                        trade.outbound(outbound.doOnNext(DisposableWrapperUtil.disposeOnForAny(trade)));
                    } catch (final Exception e) {
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
