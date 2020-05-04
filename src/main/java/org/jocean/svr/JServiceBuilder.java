package org.jocean.svr;

public interface JServiceBuilder {

    <S> S build(final Class<S> serviceType, final Object... args);

    <S> S buildFork(final String forkName, final Class<S> serviceType, final Object... args);
}
