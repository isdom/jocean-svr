package org.jocean.svr;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import rx.functions.Action1;
import rx.functions.Func0;

public class MessageDecoderUsingHolder implements MessageDecoder {
    public MessageDecoderUsingHolder(
            final Func0<? extends ByteBufHolder> getcontent, 
            final String contentType) {
        this._getcontent = getcontent;
        this._contentType = contentType;
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
    
    private final Func0<? extends ByteBufHolder> _getcontent;
    private final String _contentType;
}
