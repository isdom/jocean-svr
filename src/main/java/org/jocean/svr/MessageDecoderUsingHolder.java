package org.jocean.svr;

import java.io.InputStream;

import org.jocean.idiom.DisposableWrapper;
import org.jocean.netty.BlobRepo.Blob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufInputStream;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.subscriptions.Subscriptions;

class MessageDecoderUsingHolder implements MessageDecoder {
    
    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(MessageDecoderUsingHolder.class);
    
    private final Subscription _subscription;
    
    public MessageDecoderUsingHolder(
            final ByteBufHolder holder, 
            final int contentLength, 
            final String contentType,
            final String filename,
            final String name
            ) {
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
                return Observable.just(ParamUtil.parseContentAsJson(holder, type));
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
                return Observable.just(ParamUtil.parseContentAsXml(holder, type));
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
                            final Subscription  subscription = Subscriptions.create(new Action0() {
                                @Override
                                public void call() {
                                    ReferenceCountUtil.release(buf);
                                }});
                            if (null!=buf) {
//                                subscriber.add(Subscriptions.create(new Action0() {
//                                    @Override
//                                    public void call() {
//                                        buf.release();
//                                    }}));
                                subscriber.onNext(new DisposableWrapper<ByteBuf>() {

                                    @Override
                                    public ByteBuf unwrap() {
                                        return buf;
                                    }

                                    @Override
                                    public void dispose() {
                                        subscription.unsubscribe();
                                    }

                                    @Override
                                    public boolean isDisposed() {
                                        return subscription.isUnsubscribed();
                                    }});
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
            }});
    }
    
    @Override
    public Func0<Blob> blobProducer() {
        return new Func0<Blob>() {
            @Override
            public Blob call() {
                if (!isUnsubscribed()) {
                    return buildBlob(_holder, _contentType, _filename, _name);
                } else {
                    return null;
                }
            }};
    }

    private static Blob buildBlob(final ByteBufHolder holder,
            final String contentType, 
            final String filename,
            final String name) {
        final int length = holder.content().readableBytes();
        return new Blob() {
            @Override
            public String toString() {
                final StringBuilder builder = new StringBuilder();
                builder.append("holder-blob[holder=").append(holder)
                        .append(", name=").append(name())
                        .append(", filename=").append(filename())
                        .append(", contentType=").append(contentType())
                        .append(", content.length=").append(length)
                        .append("]");
                return builder.toString();
            }
            
            @Override
            public String contentType() {
                return contentType;
            }
            @Override
            public String name() {
                return name;
            }
            @Override
            public String filename() {
                return filename;
            }

            @Override
            public int refCnt() {
                return holder.refCnt();
            }

            @Override
            public Blob retain() {
                holder.retain();
                return this;
            }

            @Override
            public Blob retain(int increment) {
                holder.retain(increment);
                return this;
            }

            @Override
            public Blob touch() {
                holder.touch();
                return this;
            }

            @Override
            public Blob touch(Object hint) {
                holder.touch(hint);
                return this;
            }

            @Override
            public boolean release() {
                return holder.release();
            }

            @Override
            public boolean release(int decrement) {
                return holder.release(decrement);
            }

            @Override
            public InputStream inputStream() {
                return new ByteBufInputStream(holder.content().slice(), false);
            }

            @Override
            public int contentLength() {
                return length;
            }};
    }

    private final ByteBufHolder _holder;
    private final int    _contentLength;
    private final String _contentType;
    private final String _filename;
    private final String _name;
}
