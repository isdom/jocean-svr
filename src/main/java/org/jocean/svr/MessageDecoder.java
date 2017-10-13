package org.jocean.svr;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.netty.BlobRepo.Blob;

import io.netty.buffer.ByteBuf;
import rx.Observable;
import rx.functions.Func0;
import rx.Subscription;

public interface MessageDecoder extends Subscription {
    
    public String contentType();
    
    public int contentLength();
    
    public Observable<? extends DisposableWrapper<ByteBuf>> content();
    
    public <T> Observable<? extends T> decodeJsonAs(final Class<T> type);
    
    public <T> Observable<? extends T> decodeXmlAs(final Class<T> type);
    
    public <T> Observable<? extends T> decodeFormAs(final Class<T> type);
    
    public Func0<Blob> blobProducer();
}
