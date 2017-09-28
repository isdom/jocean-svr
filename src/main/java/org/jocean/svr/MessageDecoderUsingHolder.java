package org.jocean.svr;

import java.io.InputStream;

import org.jocean.netty.BlobRepo.Blob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufInputStream;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.subscriptions.Subscriptions;

public class MessageDecoderUsingHolder implements MessageDecoder {
    private static final Logger LOG
        = LoggerFactory.getLogger(MessageDecoderUsingHolder.class);
    
    public MessageDecoderUsingHolder(
            final Func0<? extends ByteBufHolder> getcontent, 
            final String contentType,
            final String filename,
            final String name
            ) {
        this._getcontent = getcontent;
        this._contentType = contentType;
        this._filename = filename;
        this._name = name;
    }

    @Override
    public <T> T decodeJsonAs(final Class<T> type) {
        final ByteBufHolder holder = this._getcontent.call();
        if (null != holder) {
            try {
                return ParamUtil.parseContentAsJson(holder, type);
            } finally {
                holder.release();
            }
        }
        return null;
    }

    @Override
    public <T> T decodeXmlAs(final Class<T> type) {
        final ByteBufHolder holder = this._getcontent.call();
        if (null != holder) {
            try {
                return ParamUtil.parseContentAsXml(holder, type);
            } finally {
                holder.release();
            }
        }
        return null;
    }

    @Override
    public <T> T decodeFormAs(final Class<T> type) {
        return null;
    }

    @Override
    public String contentType() {
        return this._contentType;
    }

    @Override
    public void visitContent(final Action1<ByteBuf> visitor) {
        final ByteBufHolder holder = this._getcontent.call();
        if (null != holder) {
            try {
                visitor.call(holder.content());
            } finally {
                holder.release();
            }
        }
    }
    
    @Override
    public Func0<Blob> blobProducer() {
        return new Func0<Blob>() {
            @Override
            public Blob call() {
                final ByteBufHolder holder = _getcontent.call();
                if (null != holder) {
                    return buildBlob(holder, _contentType, _filename, _name);
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
            }

            @Override
            public Observable<? extends ByteBuf> content() {
                return Observable.unsafeCreate(new OnSubscribe<ByteBuf>() {
                    @Override
                    public void call(final Subscriber<? super ByteBuf> subscriber) {
                        if (!subscriber.isUnsubscribed()) {
                            try {
                                final ByteBuf buf = holder.content().retainedSlice();
                                if (null!=buf) {
//                                    final HttpContent content = 
//                                        new DefaultLastHttpContent(buf);
                                    subscriber.add(Subscriptions.create(new Action0() {
                                        @Override
                                        public void call() {
                                            final boolean released = buf.release();
                                            LOG.debug("content()'s ByteBuf {} invoke release with return {}",
                                                    buf, released);
                                        }}));
                                    subscriber.onNext(buf);
                                    subscriber.onCompleted();
                                } else {
                                    subscriber.onError(new RuntimeException("invalid content"));
                                }
                            } catch (Exception e) {
                                subscriber.onError(e);
                            }
                        }
                    }});
            }};
    }

    private final Func0<? extends ByteBufHolder> _getcontent;
    private final String _contentType;
    private final String _filename;
    private final String _name;
}
