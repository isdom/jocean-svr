package org.jocean.svr;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.core.MediaType;

import org.jocean.http.DoFlush;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

public class ResponseUtil {
    
    private static final Logger LOG
        = LoggerFactory.getLogger(ResponseUtil.class);
    
    private ResponseUtil() {
        throw new IllegalStateException("No instances!");
    }
    
    static private class ACRHeader {
        @HeaderParam("access-control-request-headers")
        private String _headers;
        
        @HeaderParam("access-control-request-method")
        private String _method;
    
        @HeaderParam("origin")
        private String _origin;
    }
    
    static private class ACAOnly implements MessageResponse, MessageBody {
        @HeaderParam("access-control-allow-headers")
        private String _headers;
        
        @HeaderParam("access-control-allow-methods")
        private String _method;
    
        @HeaderParam("access-control-allow-origin")
        private String _origin;

        @HeaderParam("access-control-allow-credentials")
        private boolean _credentials = true;
        
        @Override
        public ByteBuf content() {
            return null;
        }

        @Override
        public int status() {
            return 202;
        }
    }
    
    public static Observable<Object> acceptCORS(final Observable<HttpObject> request) {
        final ACRHeader hdr = new ACRHeader();
        return request.compose(RxNettys.asHttpRequest())
                .doOnNext(ParamUtil.injectHeaderParams(hdr))
                .map(new Func1<HttpRequest, Object>() {
                    @Override
                    public Object call(final HttpRequest req) {
                        final ACAOnly aca = new ACAOnly();
                        aca._headers = hdr._headers;
                        aca._method = hdr._method;
                        aca._origin = hdr._origin;
                        return aca;
                    }})
                .delaySubscription(request.last());
    }
    
    public static Observable<Object> flushOnly() {
        return Observable.<Object>just(new FlushOnly());
    }
    
    public static Observable<Object> statusOnly(final int status) {
        return Observable.<Object>just(new StatusOnly(status));
    }
    
    public static Observable<Object> responseAsJson(final int status, final Object pojo) {
        
        final ByteBuf content = Unpooled.wrappedBuffer(JSON.toJSONBytes(pojo));
        return Observable.<Object>just(new FullResponse(status, MediaType.APPLICATION_JSON, content));
    }
    
    public static Observable<Object> responseAsXml(final int status, final Object pojo) {
        try {
            final XmlMapper mapper = new XmlMapper();
            
            final ByteBuf content = Unpooled.wrappedBuffer(mapper.writeValueAsBytes(pojo));
            return Observable.<Object>just(new FullResponse(status, MediaType.APPLICATION_XML, content));
        } catch (JsonProcessingException e) {
            LOG.warn("exception when convert {} to xml, detail: {}", pojo,
                    ExceptionUtils.exception2detail(e));
            return statusOnly(500);
        }
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
    
    private static final class FullResponse implements MessageResponse, MessageBody {
        FullResponse(final int status, final String contentType, final ByteBuf content) {
            this._status = status;
            this._contentType = contentType;
            this._content = content;
        }
        
        @Override
        public int status() {
            return this._status;
        }

        @Override
        public ByteBuf content() {
            return this._content;
        }
        
        private final int _status;
        private final ByteBuf _content;
        
        @HeaderParam("content-type")
        private final String _contentType;
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
    
}
