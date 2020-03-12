package org.jocean.svr;

import org.jocean.http.RpcExecutor;

public interface FacadeBuilder {
    <F> F build(final Class<F> facadeType, final String... preprocessors);
    <F> F build(final Class<F> facadeType, final RpcExecutor executor, final String... preprocessors);
}
