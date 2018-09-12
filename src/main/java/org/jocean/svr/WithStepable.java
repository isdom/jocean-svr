package org.jocean.svr;

import java.io.OutputStream;

import org.jocean.idiom.Stepable;

import rx.Observable;
import rx.functions.Action2;

public interface WithStepable<T extends Stepable<?>> extends WithBody {
    public String contentType();
    public Observable<T> stepables();
    public Action2<T, OutputStream> output();
}
