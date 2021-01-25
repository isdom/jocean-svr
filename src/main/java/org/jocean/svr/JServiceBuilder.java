package org.jocean.svr;

/**
 * @deprecated use {@link #JService} instead.
 */
@Deprecated
public interface JServiceBuilder {
    <S> S build(final String serviceName, final Class<S> serviceType, final Object... args);
    <S> S build(final Class<S> serviceType, final Object... args);
}
