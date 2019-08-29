package org.jocean.svr;

import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Interact;
import org.jocean.http.InteractBuilder;
import org.jocean.http.MessageUtil;
import org.jocean.http.RpcRunner;
import org.jocean.http.TypedSPI;
import org.jocean.http.client.HttpClient;
import org.jocean.http.endpoint.EndpointSet;
import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func1;

public class FinderUtil {
    private static final Logger LOG = LoggerFactory.getLogger(FinderUtil.class);

    private FinderUtil() {
        throw new IllegalStateException("No instances!");
    }

    public interface CallerContext {
        public String className();
        public String methodName();
    }

    private static CallerContext from(final StackTraceElement ste) {
        return new CallerContext() {
            @Override
            public String className() {
                return ste.getClassName();
            }
            @Override
            public String methodName() {
                return ste.getMethodName();
            }};
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

    static Func1<Interact, Observable<Interact>> endpoint(final BeanFinder finder, final TypedSPI spi) {
        return interact -> finder.find(EndpointSet.class).map(eps -> eps.of(spi).call(interact));
    }

    public static Observable<Interact> interacts(final BeanFinder finder, final InteractBuilder ib) {
        final CallerContext ctx = from(Thread.currentThread().getStackTrace()[2]);
        return finder.find(HttpClient.class).flatMap(client -> ib.interact(client))
                .compose(findAndApplyRpcConfig(finder, ctx));
    }

    public static Observable<Interact> interacts(final BeanFinder finder) {
        final CallerContext ctx = from(Thread.currentThread().getStackTrace()[2]);
        return finder.find(HttpClient.class).map(client-> MessageUtil.interact(client))
                .compose(findAndApplyRpcConfig(finder, ctx));
    }

    public interface RpcRunnerBuilder {
        public RpcRunnerBuilder ib(final InteractBuilder ib);
        public Observable<RpcRunner> runner();
    }

    public static RpcRunnerBuilder rpc(final BeanFinder finder) {
        return rpc(finder, from(Thread.currentThread().getStackTrace()[2]));
    }

    public static RpcRunnerBuilder rpc(final BeanFinder finder, final CallerContext ctx) {
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
                        return finder.find(HttpClient.class).flatMap(client-> ib.interact(client))
                                .compose(findAndApplyRpcConfig(finder, ctx))
                                .compose(interacts-> Observable.just(buildRunner(interacts, finder, ctx)));
                    }};
            }

            @Override
            public Observable<RpcRunner> runner() {
                return finder.find(HttpClient.class).map(client-> MessageUtil.interact(client))
                        .compose(findAndApplyRpcConfig(finder, ctx))
                        .compose(interacts-> Observable.just(buildRunner(interacts, finder, ctx)));
            }};
    }

    private static Transformer<? super Interact, ? extends Interact> findAndApplyRpcConfig(
            final BeanFinder finder,
            final CallerContext ctx) {
        final String callerClassName = ctx.className();
        final String callerMethodName = ctx.methodName();

        return interacts-> {
            return finder.find("rpccfg_" + callerClassName, RpcConfig.class).flatMap(cfg -> {
                final RpcConfig childcfg = cfg.child(callerMethodName);
                if (null != childcfg) {
                    LOG.debug("using {}:{}-{}'s before", callerClassName, callerMethodName, childcfg);
                    return interacts.compose(childcfg.before());
                } else {
                    LOG.debug("using {}-{}'s before", callerClassName, cfg);
                    return interacts.compose(cfg.before());
                }
            }, e -> {
                return finder.find("rpccfg_global", RpcConfig.class).flatMap(cfg -> {
                    LOG.debug("using rpccfg_global-{}'s before", cfg);
                    return interacts.compose(cfg.before());
                }, e1 -> {
                    LOG.debug("Non-Matched RpcConfig for {}:{}", callerClassName, callerMethodName);
                    return interacts;
                }, () -> Observable.empty());
            },
            () -> Observable.empty());
        };
    }

    private static RpcRunner buildRunner(final Observable<? extends Interact> interacts,
            final BeanFinder finder,
            final CallerContext ctx) {
        final AtomicReference<TypedSPI> spiRef = new AtomicReference<>();
        final AtomicReference<String> nameRef = new AtomicReference<>();
        final AtomicReference<Action1<Interact>> oninteractRef = new AtomicReference<>();

        return new RpcRunner() {
            @Override
            public RpcRunner spi(final TypedSPI spi) {
                spiRef.set(spi);
                return this;
            }

            @Override
            public RpcRunner name(final String name) {
                nameRef.set(name);
                return this;
            }

            @Override
            public RpcRunner oninteract(final Action1<Interact> oninteract) {
                final Action1<Interact> prev = oninteractRef.get();
                if (prev != null) {
                    oninteractRef.set(interact -> {
                        try {
                            prev.call(interact);
                        } catch (final Exception e) {
                            LOG.warn("exception when oninteract:{}, detail: {}", prev, ExceptionUtils.exception2detail(e));
                        }
                        try {
                            oninteract.call(interact);
                        } catch (final Exception e) {
                            LOG.warn("exception when oninteract:{}, detail: {}", oninteract, ExceptionUtils.exception2detail(e));
                        }
                    });
                }
                else {
                    oninteractRef.set(oninteract);
                }
                return this;
            }

            @Override
            public <T> Observable<T> execute(final Func1<Interact, Observable<T>> invoker) {
                return doExecute(interacts, finder, ctx, spiRef.get(), nameRef.get(), oninteractRef.get(), invoker);
            }
        };
    }

    private static <T> Observable<T> doExecute(
            final Observable<? extends Interact> interacts,
            final BeanFinder finder,
            final CallerContext ctx,
            final TypedSPI spi,
            final String name,
            final Action1<Interact> oninteract,
            final Func1<Interact, Observable<T>> invoker) {

        final String group = getSimpleClassName(ctx.className()) + "." + ctx.methodName();

        final String key = (null != spi ? spi.type() : "(api)") + "." + (null != name ? name : "(unname)");

        Observable<? extends Interact> inters = interacts;
        if (null != spi) {
            inters = inters.flatMap(endpoint(finder, spi));
        }
        if (null != oninteract) {
            inters = inters.doOnNext(oninteract);
        }
        return inters.flatMap(invoker).compose(withAfter(finder, ctx));
        /*
        return new HystrixObservableCommand<T>(
                HystrixObservableCommand.Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey(group))
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
                return inters.flatMap(invoker).compose(withAfter(finder, ctx));
            }
        }.toObservable();
        */
    }

    private static String getSimpleClassName(final String className) {
        final int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }

    private static <T> Transformer<T, T> withAfter(final BeanFinder finder,final CallerContext ctx) {
        final String callerClassName = ctx.className();
        final String callerMethodName = ctx.methodName();

        return response -> finder.find("rpccfg_" + callerClassName, RpcConfig.class).flatMap(cfg -> {
            final RpcConfig childcfg = cfg.child(callerMethodName);
            if (null != childcfg) {
                LOG.debug("using {}:{}-{}'s after", callerClassName, callerMethodName, childcfg);
                return response.compose(childcfg.after());
            } else {
                LOG.debug("using {}-{}'s after", callerClassName, cfg);
                return response.compose(cfg.after());
            }
        }, e -> {
            return finder.find("rpccfg_global", RpcConfig.class).flatMap(cfg -> {
                LOG.debug("using rpccfg_global-{}'s after", cfg);
                return response.compose(cfg.after());
            }, e1 -> {
                LOG.debug("Non-Matched RpcConfig for {}:{}", callerClassName, callerMethodName);
                return response;
            }, () -> Observable.empty());
        },
        () -> Observable.empty());
    }
}
