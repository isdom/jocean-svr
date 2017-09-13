package org.jocean.svr;

import org.jocean.http.DoFlush;
import org.jocean.http.util.RxNettys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

public class ResponseUtil {
    
    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(ResponseUtil.class);
    
    private ResponseUtil() {
        throw new IllegalStateException("No instances!");
    }
    
    private static class FlushOnly implements HttpObject, DoFlush {
        @Override
        public DecoderResult decoderResult() {
            return null;
        }

        @Override
        public void setDecoderResult(DecoderResult result) {
        }

        @Override
        public DecoderResult getDecoderResult() {
            return null;
        }
    }
    
    public static Observable<Object> flushOnly() {
        return Observable.<Object>just(new FlushOnly());
    }
    
    public static Observable<Object> statusOnly(final int status) {
        return Observable.<Object>just(new StatusOnly(status));
    }
    
    public static MessageResponse respWithStatus(final int status) {
        return new MessageResponse() {
            @Override
            public int status() {
                return status;
            }};
    }
    
    public static Transformer<Object, Object> handleExpect100(
            final Observable<HttpObject> request,
            final Func1<HttpRequest, Integer> continueHandler) {
        return new Transformer<Object, Object>() {
            @Override
            public Observable<Object> call(final Observable<Object> response) {
                return request.compose(RxNettys.asHttpRequest())
                .flatMap(new Func1<HttpRequest, Observable<Object>>() {
                    @Override
                    public Observable<Object> call(final HttpRequest req) {
                        if (!HttpUtil.is100ContinueExpected(req)) {
                            return response;
                        } else {
                            final int status = continueHandler.call(req);
                            if (status == 100) {
                                return Observable.concat(
                                    Observable.<Object>just(new StatusOnly(100), new FlushOnly()), 
                                    response);
                            } else {
                                return ResponseUtil.statusOnly(status);
                            }
                        }
                    }});
            }};
    }
    
    public static MessageBody emptyBody() {
        return EMPTY_BODY;
    }
    
    private static final MessageBody EMPTY_BODY = new MessageBody() {
        @Override
        public ByteBuf content() {
            return null;
        }};
    
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
