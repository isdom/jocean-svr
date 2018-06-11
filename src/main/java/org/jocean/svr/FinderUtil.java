package org.jocean.svr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Interact;
import org.jocean.http.InteractBuilder;
import org.jocean.http.MessageUtil;
import org.jocean.http.TypedSPI;
import org.jocean.http.client.HttpClient;
import org.jocean.http.endpoint.EndpointSet;
import org.jocean.idiom.BeanFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

public class FinderUtil {
    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(FinderUtil.class);

    private FinderUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static Observable<Interact> interacts(final BeanFinder finder, final InteractBuilder ib) {
        return finder.find(HttpClient.class).map(client-> ib.interact(client));
    }

    public static Observable<Interact> interacts(final BeanFinder finder) {
        return finder.find(HttpClient.class).map(client-> MessageUtil.interact(client));
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

    public interface RpcBuilder {
        public RpcBuilder spi(final TypedSPI spi);
        public RpcBuilder pre(final String ...processors);
        public RpcBuilder post(final String ...processors);
        public <T> Transformer<Interact, T> attach(final Func1<Interact, Observable<T>> invoker);
    }

    private static final String[] EMPTY_STRS = new String[0];

    public static RpcBuilder rpc(final BeanFinder finder) {
        final List<String> pres = new ArrayList<>();
        final List<String> posts = new ArrayList<>();
        final AtomicReference<TypedSPI> spiRef = new AtomicReference<>(null);

        return new RpcBuilder() {
            @Override
            public RpcBuilder spi(final TypedSPI spi) {
                spiRef.set(spi);
                return this;
            }
            @Override
            public RpcBuilder pre(final String... processors) {
                pres.addAll(Arrays.asList(processors));
                return this;
            }
            @Override
            public RpcBuilder post(final String... processors) {
                posts.addAll(Arrays.asList(processors));
                return this;
            }
            @Override
            public <T> Transformer<Interact, T> attach(final Func1<Interact, Observable<T>> invoker) {
                return interacts-> {
                    if (null != spiRef.get()) {
                        interacts = interacts.compose(FinderUtil.endpoint(finder, spiRef.get()));
                    }

                    if (!pres.isEmpty()) {
                        interacts = interacts.compose(processors(finder, pres.toArray(EMPTY_STRS)));
                    }

                    final Observable<T> response = interacts.flatMap(invoker);

                    return posts.isEmpty() ? response : response.compose(processors(finder, posts.toArray(EMPTY_STRS)));
                };
            }
        };
    }
}
