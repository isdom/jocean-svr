package org.jocean.svr;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import rx.Observable;

public class CORSInterceptor implements MethodInterceptor {

    @Override
    public Observable<HttpObject> preInvoke(final Context ctx) {
        if (ctx.request().method().equals(HttpMethod.OPTIONS)) {
            final String headers = 
                ctx.request().headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS);
            final String methods = 
                    ctx.request().headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD);
            final String origin = 
                    ctx.request().headers().get(HttpHeaderNames.ORIGIN);
            if (null != headers || null != methods || null != origin) {
                final DefaultFullHttpResponse corsresp = 
                        new DefaultFullHttpResponse(ctx.request().protocolVersion(), 
                                HttpResponseStatus.ACCEPTED, Unpooled.EMPTY_BUFFER);
                HttpUtil.setContentLength(corsresp, 0);
                if (null != headers) {
                    corsresp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, headers);
                }
                if (null != methods) {
                    corsresp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, methods);
                }
                if (null != origin) {
                    corsresp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                }
                corsresp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, true);
                return Observable.<HttpObject>just(corsresp)
                    .delaySubscription(ctx.obsRequest().last());
            }
        }
        return null;
    }

    @Override
    public Observable<HttpObject> postInvoke(final Context ctx) {
        return null;
    }

}
