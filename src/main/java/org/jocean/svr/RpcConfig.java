package org.jocean.svr;

import org.jocean.http.Interact;

import rx.Observable.Transformer;

public interface RpcConfig {
    public Transformer<Interact, Interact> before();
    public <T> Transformer<T, T> after();
    public RpcConfig child(final String name);
}
