package org.jocean.svr;

import org.jocean.http.MessageBody;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.Terminable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;

class MessageBodyUsingHolder implements MessageBody {
    
    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(MessageBodyUsingHolder.class);
    
    public MessageBodyUsingHolder(
            final Terminable terminable,
            final ByteBufHolder holder, 
            final int contentLength, 
            final String contentType,
            final String filename,
            final String name
            ) {
        this._terminable = terminable;
        this._holder = holder;
        this._contentLength = contentLength;
        this._contentType = contentType;
        this._filename = filename;
        this._name = name;
        
        terminable.doOnTerminate(() -> {
            if (null != holder) {
                holder.release();
            }
        });
    }

    @Override
    public String contentType() {
        return this._contentType;
    }

    @Override
    public int contentLength() {
        return this._contentLength;
    }

    @Override
    public Observable<? extends DisposableWrapper<ByteBuf>> content() {
        return Observable.unsafeCreate(new OnSubscribe<DisposableWrapper<ByteBuf>>() {
            @Override
            public void call(final Subscriber<? super DisposableWrapper<ByteBuf>> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    try {
                        final ByteBuf buf = _holder.content().retainedSlice();
                        if (null != buf) {
                            subscriber.onNext(DisposableWrapperUtil.disposeOn(_terminable, RxNettys.wrap4release(buf)));
                            subscriber.onCompleted();
                        } else {
                            subscriber.onError(new RuntimeException("invalid bytebuf"));
                        }
                    } catch (Exception e) {
                        subscriber.onError(e);
                    }
                }
            }
        });
    }
    
    private final Terminable _terminable;
    private final ByteBufHolder _holder;
    private final int    _contentLength;
    private final String _contentType;
    private final String _filename;
    private final String _name;
}
