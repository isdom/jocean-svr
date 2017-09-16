package org.jocean.svr.interceptor;

import org.jocean.http.DoFlush;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.svr.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import rx.Observable;
import rx.functions.Func1;

public class Handle100Continue implements MethodInterceptor {

    private static final Logger LOG
        = LoggerFactory.getLogger(Handle100Continue.class);
    
    public void setPredicate(final Func1<HttpRequest, Integer> predicate) {
        this._predicate = predicate;
    }

    @Override
    public Observable<HttpObject> preInvoke(final Context ctx) {
        return null;
    }

    @Override
    public Observable<HttpObject> postInvoke(final Context ctx) {
        if (!HttpUtil.is100ContinueExpected(ctx.request())) {
            return null;
        }
        
        int status = 100;
        if (null != this._predicate) {
            try {
                status = this._predicate.call(ctx.request());
            } catch (Exception e) {
                LOG.warn("exception when call 100-cintinue predicate {}, detail: {}",
                        this._predicate, ExceptionUtils.exception2detail(e));
            }
        }
        
        final DefaultFullHttpResponse resp = 
            new DefaultFullHttpResponse(ctx.request().protocolVersion(), 
                HttpResponseStatus.valueOf(status), Unpooled.EMPTY_BUFFER);
        HttpUtil.setContentLength(resp, 0);
        if (status == 100) {
            return Observable.concat(
                Observable.<HttpObject>just(resp, DoFlush.Util.flushOnly()), 
                ctx.obsResponse());
        } else {
            return Observable.<HttpObject>just(resp);
        }
    }
    
    private Func1<HttpRequest, Integer> _predicate = null;
}
