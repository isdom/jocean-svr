package org.jocean.svr.interceptor;

import org.jocean.svr.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.functions.Action1;

public class LogRest implements MethodInterceptor {
    private static final Logger LOG
    = LoggerFactory.getLogger(LogRest.class);

    @Override
    public Observable<HttpObject> preInvoke(final Context ctx) {
        LOG.debug("REST: PRE {}.{} handle {}", 
                ctx.resource(), ctx.processor(), ctx.request());
        return null;
    }

    @Override
    public Observable<HttpObject> postInvoke(final Context ctx) {
        return ctx.obsResponse().doOnNext(new Action1<HttpObject>() {
            @Override
            public void call(final HttpObject hobj) {
                if (hobj instanceof HttpResponse) {
                    LOG.debug("REST: POST {}.{} handle {}, and resp {}", 
                            ctx.resource(), ctx.processor(), ctx.request(), hobj);
                }
            }});
    }

}
