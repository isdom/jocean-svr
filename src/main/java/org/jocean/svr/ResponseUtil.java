package org.jocean.svr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import rx.Observable;

public class ResponseUtil {
    
    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(ResponseUtil.class);
    
    private ResponseUtil() {
        throw new IllegalStateException("No instances!");
    }
    
    public static Observable<Object> statusOnly(final int status) {
        return Observable.<Object>just(new StatusOnly(status));
    }
    
    private static final class StatusOnly implements MessageResponse, MessageBody {
        StatusOnly(final int status) {
            this._status = status;
        }
        
        @Override
        public int status() {
            return _status;
        }

        @Override
        public ByteBuf content() {
            return null;
        }
        
        private final int _status;
    }
}
