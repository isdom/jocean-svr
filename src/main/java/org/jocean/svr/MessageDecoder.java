package org.jocean.svr;

import org.jocean.netty.BlobRepo.Blob;

import io.netty.buffer.ByteBuf;
import rx.Observable;
import rx.functions.Func0;
import rx.Subscription;

public interface MessageDecoder extends Subscription {
    
    public String contentType();
    
    public int contentLength();
    
    public Observable<? extends ByteBuf> content();
    
    public Func0<Blob> blobProducer();
    
    public <T> T decodeJsonAs(final Class<T> type);
    
    public <T> T decodeXmlAs(final Class<T> type);
    
    public <T> T decodeFormAs(final Class<T> type);
}
