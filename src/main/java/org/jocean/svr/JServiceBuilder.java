package org.jocean.svr;

public interface JServiceBuilder {
    <S> S build(final Class<S> serviceType);
    <S> S build(final Class<S> serviceType, final String forkName);
}
