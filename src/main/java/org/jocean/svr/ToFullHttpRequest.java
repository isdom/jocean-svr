package org.jocean.svr;

import io.netty.handler.codec.http.FullHttpRequest;
import rx.Observable.Transformer;
import rx.functions.Func0;

public interface ToFullHttpRequest extends Transformer<Object, Func0<FullHttpRequest>> {

}
