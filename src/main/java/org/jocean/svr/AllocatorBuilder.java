package org.jocean.svr;

import org.jocean.idiom.DisposableWrapper;

import io.netty.buffer.ByteBuf;
import rx.functions.Func0;

public interface AllocatorBuilder {
    public Func0<DisposableWrapper<? extends ByteBuf>> build(final int pageSize);
}
