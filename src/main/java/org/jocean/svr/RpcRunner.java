package org.jocean.svr;

import org.jocean.http.Interact;
import org.jocean.http.TypedSPI;

import rx.Observable;
import rx.functions.Func1;

public interface RpcRunner {
    public RpcRunner spi(final TypedSPI spi);
    public <T> Observable<T> execute(final Func1<Interact, Observable<? extends T>> invoker);
}
