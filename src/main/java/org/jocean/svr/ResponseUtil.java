package org.jocean.svr;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.core.MediaType;

import org.jocean.http.DoFlush;
import org.jocean.http.MessageUtil;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Terminable;
import org.jocean.netty.util.ByteBufsOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action0;
import rx.functions.Action2;
import rx.functions.Func0;
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
    
    static private class ACAOnly implements MessageResponse, ResponseBody {
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
    
    public static Object flushOnly() {
        return DoFlush.Util.flushOnly();
    }
    
    public static Object statusOnly(final int status) {
        return new StatusOnly(status);
    }
    
    private static class Redirectable implements MessageResponse, ResponseBody {

        public Redirectable(final String location) {
            this._location = location;
        }
        
        @Override
        public int status() {
            return 302;
        }
        
        @Override
        public ByteBuf content() {
            return null;
        }

        @HeaderParam("location")
        private String _location;
    }
    
    public static Object redirectOnly(final String location) {
        return new Redirectable(location);
    }
    
    public static Object responseAsJson(final int status, final Object pojo) {
        return new FullResponse(status, MediaType.APPLICATION_JSON, Unpooled.wrappedBuffer(JSON.toJSONBytes(pojo)));
    }
    
    public static Object responseAsXml(final int status, final Object pojo) {
        try {
            final XmlMapper mapper = new XmlMapper();
            return new FullResponse(status, MediaType.APPLICATION_XML,
                    Unpooled.wrappedBuffer(mapper.writeValueAsBytes(pojo)));
        } catch (JsonProcessingException e) {
            LOG.warn("exception when convert {} to xml, detail: {}", pojo, ExceptionUtils.exception2detail(e));
            return statusOnly(500);
        }
    }
    
    public static Object responseAsText(final int status, final String text) {
        return new FullResponse(status, MediaType.TEXT_PLAIN, Unpooled.wrappedBuffer(text.getBytes(Charsets.UTF_8)));
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
                                    Observable.<Object>just(new StatusOnly(100), DoFlush.Util.flushOnly()), 
                                    response);
                            } else {
                                return Observable.<Object>just(ResponseUtil.statusOnly(status));
                            }
                        }
                    }});
            }};
    }
    
    private static final Transformer<Object, Object> DEFAULT_ERROR_HANDLER = new Transformer<Object, Object>() {
        @Override
        public Observable<Object> call(final Observable<Object> response) {
            return response.onErrorResumeNext(error -> Observable.just(responseAsText(200,
                    null != error.getMessage() ? error.getMessage() : ExceptionUtils.exception2detail(error))));
        }
    };
    
    public static Transformer<Object, Object> defaultErrorHandler() {
        return DEFAULT_ERROR_HANDLER;
    }
    
    public static ResponseBody emptyBody() {
        return EMPTY_BODY;
    }
    
    private static final ResponseBody EMPTY_BODY = new ResponseBody() {
        @Override
        public ByteBuf content() {
            return null;
        }};
    
    private static final class FullResponse implements MessageResponse, ResponseBody {
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
        
    private static final class StatusOnly implements MessageResponse, ResponseBody {
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
    
    public static Observable<Object> response(final int status) {
        return Observable.<Object>just(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status)), 
                LastHttpContent.EMPTY_LAST_CONTENT);
    }
    
    public static class Serializer {
        public String contentType;
        public Action2<Object, OutputStream> encoder;
        
        Serializer(final String contentType, final Action2<Object, OutputStream> encoder) {
            this.contentType = contentType;
            this.encoder = encoder;
        }
    }
    
    public static final Serializer TOXML = new Serializer(MediaType.APPLICATION_XML, MessageUtil::serializeToXml);
    public static final Serializer TOJSON = new Serializer(MediaType.APPLICATION_JSON, MessageUtil::serializeToJson);
    
    public interface ResponseBuilder {
        
        public ResponseBuilder status(final int status);
        
        public ResponseBuilder body(final Object bodyPojo, final Serializer serializer);
        
        public ResponseBuilder disposeBodyOnTerminate(final boolean doDispose);
        
        public Observable<Object> build();
    }
    
    public static ResponseBuilder response() {
        final AtomicReference<Observable<Object>> responseRef = new AtomicReference<>(response(200));
        return new ResponseBuilder() {

            @Override
            public ResponseBuilder status(final int status) {
                responseRef.set(responseRef.get().doOnNext(obj -> {
                    if (obj instanceof HttpResponse) {
                        ((HttpResponse)obj).setStatus(HttpResponseStatus.valueOf(status));
                    }
                }));
                return this;
            }

            @Override
            public ResponseBuilder body(final Object bodyPojo, final Serializer serializer) {
                responseRef.set(responseRef.get().compose(MessageUtil.addBody(MessageUtil.toBody(
                        bodyPojo, serializer.contentType, serializer.encoder))));
                return this;
            }

            @Override
            public ResponseBuilder disposeBodyOnTerminate(boolean doDispose) {
                return this;
            }

            @Override
            public Observable<Object> build() {
                return responseRef.get();
            }
        };
    }
    
    public static Observable.Transformer<HttpObject, Object> toZip(
            final String zippedName,
            final String contentName,
            final Terminable terminable,
            final Func0<ByteBuf> newBuffer) {
        return new Observable.Transformer<HttpObject, Object>() {
            @Override
            public Observable<Object> call(final Observable<HttpObject> obsResponse) {
                
                final ByteBufsOutputStream bufout = new ByteBufsOutputStream(newBuffer, null);
                final ZipOutputStream zipos = new ZipOutputStream(bufout, CharsetUtil.UTF_8);
                zipos.setLevel(Deflater.BEST_COMPRESSION);
                
                terminable.doOnTerminate(() -> {
                    try {
                        zipos.close();
                    } catch (IOException e1) {
                    }
                });
                
                return obsResponse.flatMap(RxNettys.splitFullHttpMessage())
                .flatMap(httpobj -> {
                    if (httpobj instanceof HttpResponse) {
                        return Observable.concat(onResponse((HttpResponse)httpobj, zippedName), 
                                bufout2bufs(bufout, addZipEntry(zipos, contentName)).map(todwb(terminable)));
                    } else if (httpobj instanceof HttpContent) {
                        return zipContent(zipos, bufout, (HttpContent)httpobj).map(todwb(terminable));
                    } else {
                        return Observable.just(httpobj);
                    }},
                    e -> Observable.error(e),
                    () -> Observable.concat(bufout2bufs(bufout, finishZip(zipos)).map(todwb(terminable)), 
                            Observable.just(LastHttpContent.EMPTY_LAST_CONTENT))
                );
            }
        };
    }

    private static Observable<? extends Object> onResponse(final HttpResponse resp,  final String zipedName) {
        HttpUtil.setTransferEncodingChunked(resp, true);
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM);
        resp.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=" + zipedName);
        return Observable.just(resp);
    }
    
    private static Action0 addZipEntry(final ZipOutputStream zipos, final String contentName) {
        return ()-> {
            try {
                final ZipEntry entry = new ZipEntry(contentName);
                zipos.putNextEntry(entry);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
    
    private static Observable<? extends ByteBuf> zipContent(final ZipOutputStream zipos, 
            final ByteBufsOutputStream bufout,
            final HttpContent content) {
        if (content.content().readableBytes() == 0) {
            return Observable.empty();
        }
        
        return bufout2bufs(bufout, zipContent(zipos, content));
    }

    private static Action0 zipContent(final ZipOutputStream zipos, final HttpContent content) {
        return ()->{
            final ByteBufInputStream is = new ByteBufInputStream(content.content());
            try {
                final byte[] bytes = ByteStreams.toByteArray(is);
                zipos.write(bytes);
                zipos.flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static Action0 finishZip(final ZipOutputStream zipos) {
        return ()->{
            try {
                zipos.closeEntry();
                zipos.finish();
                zipos.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static Observable<ByteBuf> bufout2bufs(final ByteBufsOutputStream bufout, final Action0 fillcontent) {
        return Observable.unsafeCreate(subscriber -> {
            if (!subscriber.isUnsubscribed()) {
                bufout.setOnBuffer(buf->subscriber.onNext(buf));
                try {
                    fillcontent.call();
                } catch (Exception e) {
                    subscriber.onError(e);
                } finally {
                    subscriber.onCompleted();
                }
            }
        });
    }

    private static Func1<ByteBuf, DisposableWrapper<ByteBuf>> todwb(final Terminable terminable) {
        return DisposableWrapperUtil.<ByteBuf>wrap(RxNettys.<ByteBuf>disposerOf(), terminable);
    }
}
