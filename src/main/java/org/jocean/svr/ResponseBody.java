package org.jocean.svr;

import io.netty.buffer.ByteBuf;

public interface ResponseBody {
    public ByteBuf content();
}
