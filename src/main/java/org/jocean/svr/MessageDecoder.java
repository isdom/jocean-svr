package org.jocean.svr;

import org.jocean.netty.BlobRepo.Blob;

import io.netty.buffer.ByteBuf;
import rx.functions.Action1;

public interface MessageDecoder {
    
    public String contentType();
    
    public void visitContent(final Action1<ByteBuf> visitor);
    
    public void visitContentAsBlob(final Action1<Blob> visitor);
    
    public <T> T decodeJsonAs(final Class<T> type);
    
    public <T> T decodeXmlAs(final Class<T> type);
    
    public <T> T decodeFormAs(final Class<T> type);
}
