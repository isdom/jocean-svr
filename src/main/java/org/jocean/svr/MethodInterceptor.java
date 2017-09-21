package org.jocean.svr;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    
    public static class Util {
        
        private static final Logger LOG
        = LoggerFactory.getLogger(Util.class);
        
        @SuppressWarnings("unchecked")
        public static Class<? extends MethodInterceptor>[] str2types(final String interceptors) {
            final List<Class<? extends MethodInterceptor>> types = new ArrayList<>();
            final String[] strs = interceptors.split(",");
            for (String s : strs) {
                try {
                    final Class<?> t = Class.forName(s.trim());
                    if (MethodInterceptor.class.isAssignableFrom(t)) {
                        types.add((Class<? extends MethodInterceptor>) t);
                    } else {
                        LOG.warn("type: {} is not MethodInterceptor, just ignore.", s);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when build interceptor type: {}, detail: {}",
                            s, ExceptionUtils.exception2detail(e));
                }
            }
            return types.toArray(new Class[0]);
        }
    }
}
