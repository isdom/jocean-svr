package org.jocean.svr;

import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpObject;
import rx.Observable.Transformer;
import rx.functions.Func0;

public interface ToFullHttpMessage extends Transformer<HttpObject, Func0<FullHttpMessage>> {

}
