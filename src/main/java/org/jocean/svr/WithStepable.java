package org.jocean.svr;

import java.io.OutputStream;

import org.jocean.idiom.Stepable;

import rx.Observable;
import rx.functions.Action2;

public interface WithStepable<T extends Stepable<?>> {
    public String contentType();
    public Observable<T> content();
    public Action2<T, OutputStream> out();
}
