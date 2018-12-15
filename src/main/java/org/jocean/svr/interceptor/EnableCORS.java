package org.jocean.svr.interceptor;

import org.jocean.idiom.StepableUtil;
import org.jocean.svr.MethodInterceptor;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import rx.Observable;

public class EnableCORS implements MethodInterceptor {

    @Override
    public Observable<? extends Object> preInvoke(final Context ctx) {
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
                return Observable.<HttpObject>just(corsresp).delaySubscription(
                        ctx.obsRequest().flatMap(fullmsg -> fullmsg.body()).flatMap(body -> body.content())
                        .compose(StepableUtil.autostep2element2()).doOnNext(bbs -> bbs.dispose()).ignoreElements());
            }
        }
        return null;
    }

    @Override
    public Observable<? extends Object> postInvoke(final Context ctx) {
        final String origin = ctx.request().headers().get(HttpHeaderNames.ORIGIN);
        if (null != origin) {
            return ctx.obsResponse().doOnNext(obj -> {
                    if (obj instanceof HttpResponse) {
                        final HttpResponse response = (HttpResponse)obj;
                        if (!response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)) {
                            response.headers().set(
                                HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                            response.headers().set(
                                HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, true);
                        }
                    }
                });
        } else {
            return null;
        }
    }
}
