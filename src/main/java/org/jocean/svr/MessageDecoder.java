package org.jocean.svr;

import io.netty.buffer.ByteBuf;
import rx.functions.Action1;

public interface MessageDecoder {
    
    public String contentType();
    
    public void visitContent(final Action1<ByteBuf> visitor);
    
    public <T> T decodeJsonAs(final Class<T> type);
    
    public <T> T decodeXmlAs(final Class<T> type);
    
    public <T> T decodeFormAs(final Class<T> type);
}
