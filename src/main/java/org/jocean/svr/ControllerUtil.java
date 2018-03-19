package org.jocean.svr;

import org.jocean.http.Interact;
import org.jocean.http.InteractBuilder;
import org.jocean.http.MessageUtil;
import org.jocean.http.client.HttpClient;
import org.jocean.idiom.BeanFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;

public class ControllerUtil {
    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(ControllerUtil.class);
    
    private ControllerUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static Observable<Interact> interacts(final BeanFinder finder, final InteractBuilder ib) {
        return finder.find(HttpClient.class).map(client-> ib.interact(client));
    }

    public static Observable<Interact> interacts(final BeanFinder finder) {
        return finder.find(HttpClient.class).map(client-> MessageUtil.interact(client));
    }
}
