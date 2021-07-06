package org.jocean.svr.interceptor;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.svr.MethodInterceptor;

import rx.Observable;

public class ProcessorConcurrentLimiter implements MethodInterceptor {

    @Override
    public Observable<? extends Object> preInvoke(final Context ctx) {
        // ctx.processor();
        return null;
    }

    @Override
    public Observable<? extends Object> postInvoke(final Context ctx) {
        return null;
    }

    final ConcurrentMap<Method, AtomicInteger> _concurrentCounter = new ConcurrentHashMap<>();
}
