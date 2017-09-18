package org.jocean.svr;

import org.jocean.netty.BlobRepo.Blob;

import io.netty.buffer.ByteBuf;
import rx.functions.Action1;
import rx.functions.Func0;

public interface MessageDecoder {
    
    public String contentType();
    
    public void visitContent(final Action1<ByteBuf> visitor);
    
    public Func0<Blob> blobProducer();
    
    public <T> T decodeJsonAs(final Class<T> type);
    
    public <T> T decodeXmlAs(final Class<T> type);
    
    public <T> T decodeFormAs(final Class<T> type);
}
