package org.jocean.svr;

import java.lang.reflect.Method;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;

public interface MethodInterceptor {
    public interface Context {
        public Object resource();
        public Method processor();
        public HttpRequest request();
        public Observable<? extends HttpObject> obsRequest();
        public Observable<HttpObject> obsResponse();
    }
    
    public Observable<HttpObject> preInvoke(final Context ctx);

    public Observable<HttpObject> postInvoke(final Context ctx);
}
