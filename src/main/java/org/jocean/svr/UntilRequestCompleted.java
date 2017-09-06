package org.jocean.svr;

import rx.Observable.Transformer;

public interface UntilRequestCompleted<T> extends Transformer<T, T> {

}
