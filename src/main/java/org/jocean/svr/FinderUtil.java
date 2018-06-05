package org.jocean.svr;

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
}
