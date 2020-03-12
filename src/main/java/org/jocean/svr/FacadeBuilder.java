package org.jocean.svr;

public interface FacadeBuilder {
    <F> F build(Class<F> facadeType, final String... preprocessors);
}
