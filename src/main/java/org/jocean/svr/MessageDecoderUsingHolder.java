package org.jocean.svr;

import java.io.InputStream;

import org.jocean.netty.BlobRepo.Blob;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufInputStream;
import rx.functions.Action1;
import rx.functions.Func0;

public class MessageDecoderUsingHolder implements MessageDecoder {
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
    // 

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
    public void visitContentAsBlob(final Action1<Blob> visitor) {
        final ByteBufHolder holder = this._getcontent.call();
        if (null != holder) {
            try {
                visitor.call(buildBlob(holder.content(), _contentType, _filename, _name));
            } finally {
                holder.release();
            }
        }
    }
    
    private static Blob buildBlob(final ByteBuf bytebuf,
            final String contentType, 
            final String filename,
            final String name) {
        final int length = bytebuf.readableBytes();
        return new Blob() {
            @Override
            public String toString() {
                final StringBuilder builder = new StringBuilder();
                builder.append("Blob [name=").append(name())
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
                return bytebuf.refCnt();
            }

            @Override
            public Blob retain() {
                bytebuf.retain();
                return this;
            }

            @Override
            public Blob retain(int increment) {
                bytebuf.retain(increment);
                return this;
            }

            @Override
            public Blob touch() {
                bytebuf.touch();
                return this;
            }

            @Override
            public Blob touch(Object hint) {
                bytebuf.touch(hint);
                return this;
            }

            @Override
            public boolean release() {
                return bytebuf.release();
            }

            @Override
            public boolean release(int decrement) {
                return bytebuf.release(decrement);
            }

            @Override
            public InputStream inputStream() {
                return new ByteBufInputStream(bytebuf.slice(), false);
            }

            @Override
            public int contentLength() {
                return length;
            }};
    }

    private final Func0<? extends ByteBufHolder> _getcontent;
    private final String _contentType;
    private final String _filename;
    private final String _name;
}
