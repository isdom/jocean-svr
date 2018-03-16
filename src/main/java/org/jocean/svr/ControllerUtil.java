package org.jocean.svr;

import org.jocean.http.Interact;
import org.jocean.http.InteractBuilder;
import org.jocean.http.MessageUtil;
import org.jocean.http.client.HttpClient;
import org.jocean.idiom.BeanFinder;
import org.jocean.idiom.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.functions.Func2;

public class ControllerUtil {
    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(ControllerUtil.class);
    
    private ControllerUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static <API, T> Observable<T> interactWith(final BeanFinder finder, final Class<API> apicls,
            final InteractBuilder ib, final Func2<API, Interact, Observable<T>> callapi) {
        return Observable.zip(finder.find(apicls), finder.find(HttpClient.class).map(client -> ib.interact(client)),
                (api, interact) -> Pair.of(api, interact)).flatMap(pair -> {
                    final API api = pair.first;
                    final Interact interact = pair.second;
                    return callapi.call(api, interact);
                });
    }

    public static <API, T> Observable<T> interactWith(final BeanFinder finder, final Class<API> apicls,
            final Func2<API, Interact, Observable<T>> callapi) {
        return Observable.zip(finder.find(apicls), 
                finder.find(HttpClient.class).map(client -> MessageUtil.interact(client)),
                (api, interact) -> Pair.of(api, interact)).flatMap(pair -> {
                    final API api = pair.first;
                    final Interact interact = pair.second;
                    return callapi.call(api, interact);
                });
    }
}
