package org.jocean.svr;

import java.io.OutputStream;

import rx.Subscriber;
import rx.functions.Action2;

public interface WithSubscriber<T> extends WithBody {
    public String contentType();
    public void onSubscriber(final Subscriber<T> Subscriber);
    public Action2<T, OutputStream> output();
}
