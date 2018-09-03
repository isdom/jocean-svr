package org.jocean.svr;

import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.core.MediaType;

import org.jocean.http.DoFlush;
import org.jocean.http.MessageBody;
import org.jocean.http.MessageUtil;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.common.base.Charsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

public class ResponseUtil {

    private static final Logger LOG
        = LoggerFactory.getLogger(ResponseUtil.class);

    private ResponseUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static Object flushOnly() {
        return DoFlush.Util.flushOnly();
    }

    public static Object statusOnly(final int status) {
        return new StatusOnly(status);
    }

    public static class Redirectable extends HeaderOnly implements WithStatus {

        public Redirectable(final String location) {
            this._location = location;
        }

        @Override
        public int status() {
            return 302;
        }

        public String location() {
            return this._location;
        }

        @HeaderParam("location")
        private final String _location;
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
        } catch (final JsonProcessingException e) {
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

    private static final class StatusOnly extends HeaderOnly implements WithStatus {
        StatusOnly(final int status) {
            this._status = status;
        }

        @Override
        public int status() {
            return _status;
        }

        private final int _status;
    }

    public static Observable<Object> response(final int status) {
        return Observable.<Object>just(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status)),
                LastHttpContent.EMPTY_LAST_CONTENT);
    }

    public interface ResponseBuilder {

        public ResponseBuilder status(final int status);

        public ResponseBuilder body(final Observable<? extends MessageBody> body);

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
            public ResponseBuilder body(final Observable<? extends MessageBody> body) {
                responseRef.set(responseRef.get().compose(MessageUtil.addBody(body)));
                return this;
            }

            @Override
            public Observable<Object> build() {
                return responseRef.get();
            }
        };
    }
}
