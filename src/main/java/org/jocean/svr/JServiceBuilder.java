package org.jocean.svr;

public interface JServiceBuilder {
    <S> S build(final Class<S> serviceType, final Object... args);
}
