package org.jocean.svr;

public interface FacadeBuilder {
    <F> F build(final Class<F> facadeType, final String... preprocessors);
}
