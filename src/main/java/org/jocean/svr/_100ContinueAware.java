package org.jocean.svr;

import io.netty.handler.codec.http.HttpRequest;
import rx.functions.Func1;

public interface _100ContinueAware {
    public void setPredicate(final Func1<HttpRequest, Integer> predicate);
}
