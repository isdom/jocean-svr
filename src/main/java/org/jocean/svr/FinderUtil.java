package org.jocean.svr;

import org.jocean.http.Interact;
import org.jocean.http.InteractBuilder;
import org.jocean.http.MessageUtil;
import org.jocean.http.RpcRunner;
import org.jocean.http.TypedSPI;
import org.jocean.http.client.HttpClient;
import org.jocean.http.endpoint.EndpointSet;
import org.jocean.idiom.BeanFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixObservableCommand;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

public class FinderUtil {
    private static final Logger LOG
        = LoggerFactory.getLogger(FinderUtil.class);

    private FinderUtil() {
        throw new IllegalStateException("No instances!");
    }

    private static Transformer<? super Interact, ? extends Interact> findAndApplyRpcConfig(
            final BeanFinder finder,
            final StackTraceElement ste) {
        final String callerClassName = ste.getClassName();
        final String callerMethodName = ste.getMethodName();
        return interacts-> {
            return finder.find("rpccfg_" + callerClassName, RpcConfig.class).flatMap(cfg -> {
                final RpcConfig childcfg = cfg.child(callerMethodName);
                if (null != childcfg) {
                    LOG.info("using {}:{}-{}'s before", callerClassName, callerMethodName, childcfg);
                    return interacts.compose(childcfg.before());
                } else {
                    LOG.info("using {}-{}'s before", callerClassName, cfg);
                    return interacts.compose(cfg.before());
                }
            }, e -> {
                return finder.find("rpccfg_global", RpcConfig.class).flatMap(cfg -> {
                    LOG.info("using rpccfg_global-{}'s before", cfg);
                    return interacts.compose(cfg.before());
                }, e1 -> {
                    LOG.info("Non-Matched RpcConfig for {}:{}", callerClassName, callerMethodName);
                    return interacts;
                }, () -> Observable.empty());
            },
            () -> Observable.empty());
        };
    }

    public static Observable<Interact> interacts(final BeanFinder finder, final InteractBuilder ib) {
        final StackTraceElement[] stes = Thread.currentThread().getStackTrace();
        return finder.find(HttpClient.class).map(client -> ib.interact(client))
                .compose(findAndApplyRpcConfig(finder, stes[2]));
    }

    public static Observable<Interact> interacts(final BeanFinder finder) {
        final StackTraceElement[] stes = Thread.currentThread().getStackTrace();
        return finder.find(HttpClient.class).map(client-> MessageUtil.interact(client))
                .compose(findAndApplyRpcConfig(finder, stes[2]));
    }

    @SuppressWarnings("unchecked")
    public static <T> Transformer<T, T> processor(final BeanFinder finder, final String name) {
        if (null != name) {
            return source -> finder.find(name, Transformer.class)
                    .flatMap(transformer -> (Observable<T>) source.compose(transformer),
                            e -> source, () -> Observable.empty());
        } else {
            return obs -> obs;
        }
    }

    public static <T> Transformer<T, T> processors(final BeanFinder finder, final String ...names) {
        return obs -> {
            for (final String name : names) {
                obs = obs.compose(processor(finder, name));
            }
            return obs;
        };
    }

    public static Transformer<Interact, Interact> endpoint(final BeanFinder finder, final TypedSPI spi) {
        return interacts -> finder.find(EndpointSet.class).flatMap(eps -> interacts.compose(eps.of(spi)));
    }

    public interface RpcRunnerBuilder {
        public RpcRunnerBuilder ib(final InteractBuilder ib);
        public Observable<RpcRunner> runner();
    }

    public static RpcRunnerBuilder rpc(final BeanFinder finder) {
        final StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
        return new RpcRunnerBuilder() {
            @Override
            public RpcRunnerBuilder ib(final InteractBuilder ib) {
                return new RpcRunnerBuilder() {
                    @Override
                    public RpcRunnerBuilder ib(final InteractBuilder otherib) {
                        throw new RuntimeException("InteractBuilder has already set to " + ib);
                    }

                    @Override
                    public Observable<RpcRunner> runner() {
                        return finder.find(HttpClient.class).map(client-> ib.interact(client))
                                .compose(findAndApplyRpcConfig(finder, ste))
                                .compose(interacts-> Observable.just(buildRunner(interacts, finder, ste)));
                    }};
            }

            @Override
            public Observable<RpcRunner> runner() {
                return finder.find(HttpClient.class).map(client-> MessageUtil.interact(client))
                        .compose(findAndApplyRpcConfig(finder, ste))
                        .compose(interacts-> Observable.just(buildRunner(interacts, finder, ste)));
            }};
    }

    private static RpcRunner buildRunner(final Observable<? extends Interact> interacts,
            final BeanFinder finder,
            final StackTraceElement ste) {
        return new RpcRunner() {
            @Override
            public RpcRunner spi(final TypedSPI spi) {
                return new RpcRunner() {
                    @Override
                    public RpcRunner spi(final TypedSPI otherSpi) {
                        throw new RuntimeException("spi has already set to " + spi.type());
                    }
                    @Override
                    public RpcRunner name(final String name) {
                        return finalRunner(interacts, finder, ste, spi, name);
                    }
                    @Override
                    public <T> Observable<T> execute(final Func1<Interact, Observable<T>> invoker) {
                        return doExecute(interacts, finder, ste, spi, null, invoker);
                    }};
            }

            @Override
            public RpcRunner name(final String name) {
                return new RpcRunner() {
                    @Override
                    public RpcRunner spi(final TypedSPI spi) {
                        return finalRunner(interacts, finder, ste, spi, name);
                    }
                    @Override
                    public RpcRunner name(final String otherName) {
                        throw new RuntimeException("name has already set to " + name);
                    }
                    @Override
                    public <T> Observable<T> execute(final Func1<Interact, Observable<T>> invoker) {
                        return doExecute(interacts, finder, ste, null, name, invoker);
                    }};
            }

            @Override
            public <T> Observable<T> execute(final Func1<Interact, Observable<T>> invoker) {
                return doExecute(interacts, finder, ste, null, null, invoker);
            }
        };
    }

    private static RpcRunner finalRunner(
            final Observable<? extends Interact> interacts,
            final BeanFinder finder,
            final StackTraceElement ste,
            final TypedSPI spi,
            final String name) {
        return new RpcRunner() {

            @Override
            public RpcRunner spi(final TypedSPI otherSpi) {
                throw new RuntimeException("spi has already set to " + spi.type());
            }

            @Override
            public RpcRunner name(final String otherName) {
                throw new RuntimeException("name has already set to " + name);
            }

            @Override
            public <T> Observable<T> execute(final Func1<Interact, Observable<T>> invoker) {
                return doExecute(interacts, finder, ste, spi, name, invoker);
            }};
    }

    private static <T> Observable<T> doExecute(
            final Observable<? extends Interact> interacts,
            final BeanFinder finder,
            final StackTraceElement ste,
            final TypedSPI spi,
            final String name,
            final Func1<Interact, Observable<T>> invoker) {
        final String key = ste.getClassName() + "." + ste.getMethodName()
            + (null != spi ? "/" + spi.type() : "")
            + (null != name ? "/" + name : "");

        return new HystrixObservableCommand<T>(
                HystrixObservableCommand.Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("rpc"))
                        .andCommandKey(HystrixCommandKey.Factory.asKey(key))
                        .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                                // .withExecutionTimeoutEnabled(false)
                                .withExecutionTimeoutInMilliseconds(30 * 1000)
                                .withExecutionIsolationSemaphoreMaxConcurrentRequests(1000))) {
            @Override
            protected Observable<T> construct() {
                Observable<? extends Interact> inters = interacts;
                if (null != spi) {
                    inters = inters.compose(FinderUtil.endpoint(finder, spi));
                }
                return inters.flatMap(invoker).compose(withAfter(finder, ste));
            }
        }.toObservable();
    }

    private static <T> Transformer<T, T> withAfter(final BeanFinder finder, final StackTraceElement ste) {
        final String callerClassName = ste.getClassName();
        final String callerMethodName = ste.getMethodName();
        return response -> finder.find("rpccfg_" + callerClassName, RpcConfig.class).flatMap(cfg -> {
            final RpcConfig childcfg = cfg.child(callerMethodName);
            if (null != childcfg) {
                LOG.info("using {}:{}-{}'s after", callerClassName, callerMethodName, childcfg);
                return response.compose(childcfg.after());
            } else {
                LOG.info("using {}-{}'s after", callerClassName, cfg);
                return response.compose(cfg.after());
            }
        }, e -> {
            return finder.find("rpccfg_global", RpcConfig.class).flatMap(cfg -> {
                LOG.info("using rpccfg_global-{}'s after", cfg);
                return response.compose(cfg.after());
            }, e1 -> {
                LOG.info("Non-Matched RpcConfig for {}:{}", callerClassName, callerMethodName);
                return response;
            }, () -> Observable.empty());
        },
        () -> Observable.empty());
    }
}
