package org.jocean.svr;

import io.netty.buffer.ByteBuf;

public interface MessageBody {
    public ByteBuf content();
}
