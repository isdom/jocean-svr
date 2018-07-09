package org.jocean.svr.interceptor;

import org.jocean.svr.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;

public class LogRest implements MethodInterceptor {
    private static final Logger LOG
        = LoggerFactory.getLogger(LogRest.class);

    @Override
    public Observable<? extends Object> preInvoke(final Context ctx) {
        LOG.debug("REST - Before Processor: [{}]\r\n[{}]\r\n[ handle req ]:\r\n{}",
                ctx.resource(), ctx.processor(), ctx.request());
        return null;
    }

    @Override
    public Observable<? extends Object> postInvoke(final Context ctx) {
        return ctx.obsResponse().doOnNext(obj -> {
                if (obj instanceof HttpResponse) {
                    LOG.debug("REST - After Processor: [{}]\r\n[{}]\r\n[ handle req ]:\r\n{}\r\n[ and resp ]:\r\n{}",
                            ctx.resource(), ctx.processor(), ctx.request(), obj);
                }
            });
    }

}
