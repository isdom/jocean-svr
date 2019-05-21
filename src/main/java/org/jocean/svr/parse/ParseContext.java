package org.jocean.svr.parse;

import org.jocean.http.ByteBufSlice;
import org.jocean.idiom.DisposableWrapper;

import io.netty.buffer.ByteBuf;
import rx.functions.Func1;

public interface ParseContext<E> {
    void appendContent(Iterable<DisposableWrapper<? extends ByteBuf>> dwbs, Func1<ByteBufSlice, E> content2entity);
    void stopParsing();
}
