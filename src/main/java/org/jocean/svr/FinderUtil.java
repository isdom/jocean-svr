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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func1;

// revert FinderUtil visible level to public for xbeacon
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

    public static Func1<Interact, Observable<Interact>> endpoint(final BeanFinder finder, final TypedSPI spi) {
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

    static RpcRunnerBuilder rpc(final BeanFinder finder, final CallerContext ctx) {
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
                        return Observable.defer(() -> Observable.just(buildRunner(ib, finder, ctx)));
                    }};
            }

            @Override
            public Observable<RpcRunner> runner() {
                return Observable.defer(() -> Observable.just(buildRunner(
                        client -> Observable.just(MessageUtil.interact(client)), finder, ctx)));
            }};
    }

    private static RpcRunner buildRunner(final InteractBuilder ib, final BeanFinder finder, final CallerContext ctx) {
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
            public <T> Observable<T> execute(final Func1<Interact, Observable<T>> invoker) {
                return doExecute(ib, finder, ctx, spiRef.get(), nameRef.get(), oninteractRef.get(),
                        (Transformer<Interact, T>)interacts -> interacts.flatMap(invoker));
            }

            @Override
            public <T> Observable<T> submit(final Transformer<Interact, T> invoker) {
                return doExecute(ib, finder, ctx, spiRef.get(), nameRef.get(), oninteractRef.get(), invoker);
            }
        };
    }

    private static <T> Observable<T> doExecute(
            final InteractBuilder ib,
            final BeanFinder finder,
            final CallerContext ctx,
            final TypedSPI spi,
            final String name,
            final Action1<Interact> oninteract,
            final Transformer<Interact, T> invoker) {
//        final String group = getSimpleClassName(ctx.className()) + "." + ctx.methodName();
//
//        final String key = (null != spi ? spi.type() : "(api)") + "." + (null != name ? name : "(unname)");

        Observable<? extends Interact> interacts =
            finder.find(HttpClient.class).flatMap(client-> ib.interact(client))
                .compose(findAndApplyRpcConfig(finder, ctx));
        if (null != spi) {
            interacts = interacts.flatMap(endpoint(finder, spi));
        }
        if (null != oninteract) {
            interacts = interacts.doOnNext(oninteract);
        }
        return interacts.compose(invoker).compose(withAfter(finder, ctx));
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
