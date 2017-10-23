package org.jocean.svr;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.TerminateAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.subscriptions.Subscriptions;

class MessageDecoderUsingHolder implements MessageDecoder {
    
    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(MessageDecoderUsingHolder.class);
    
    private final Subscription _subscription;
    
    public MessageDecoderUsingHolder(
            final TerminateAware<?> terminateAware,
            final ByteBufHolder holder, 
            final int contentLength, 
            final String contentType,
            final String filename,
            final String name
            ) {
        this._terminateAware = terminateAware;
        this._holder = holder;
        this._contentLength = contentLength;
        this._contentType = contentType;
        this._filename = filename;
        this._name = name;
        
        this._subscription = Subscriptions.create(new Action0() {
            @Override
            public void call() {
                if (null != holder) {
                    _holder.release();
                }
            }});
    }

    @Override
    public void unsubscribe() {
        this._subscription.unsubscribe();
    }

    @Override
    public boolean isUnsubscribed() {
        return this._subscription.isUnsubscribed();
    }
    
    @Override
    public <T> Observable<? extends T> decodeJsonAs(final Class<T> type) {
        final ByteBufHolder holder = this._holder.retain();
        if (null != holder) {
            try {
                return Observable.just(ParamUtil.parseContentAsJson(holder.content(), type));
            } finally {
                holder.release();
            }
        }
        return null;
    }

    @Override
    public <T> Observable<? extends T> decodeXmlAs(final Class<T> type) {
        final ByteBufHolder holder = this._holder.retain();
        if (null != holder) {
            try {
                return Observable.just(ParamUtil.parseContentAsXml(holder.content(), type));
            } finally {
                holder.release();
            }
        }
        return null;
    }

    @Override
    public <T> Observable<? extends T> decodeFormAs(final Class<T> type) {
        return Observable.error(new UnsupportedOperationException());
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
                    if (!isUnsubscribed()) {
                        try {
                            final ByteBuf buf = _holder.content().retainedSlice();
                            if (null != buf) {
                                subscriber.onNext(DisposableWrapperUtil.disposeOn(_terminateAware, RxNettys.wrap4release(buf)));
                                subscriber.onCompleted();
                            } else {
                                subscriber.onError(new RuntimeException("invalid bytebuf"));
                            }
                        } catch (Exception e) {
                            subscriber.onError(e);
                        }
                    } else {
                        subscriber.onError(new RuntimeException("content has been unsubscribed"));
                    }
                }
            }
        });
    }
    
    private final TerminateAware<?> _terminateAware;
    private final ByteBufHolder _holder;
    private final int    _contentLength;
    private final String _contentType;
    private final String _filename;
    private final String _name;
}
