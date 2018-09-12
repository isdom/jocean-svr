package org.jocean.svr;

import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.core.MediaType;

import org.jocean.http.DoFlush;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpHeaderNames;
import rx.Observable;
import rx.Observable.Transformer;

public class ResponseUtil {

    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(ResponseUtil.class);

    private ResponseUtil() {
        throw new IllegalStateException("No instances!");
    }

    private static class DefaultResponseBean implements MutableResponseBean {

        @Override
        public WithStatus withStatus() {
            return this._withStatus;
        }

        @Override
        public WithHeader withHeader() {
            return this._withHeader;
        }

        @Override
        public WithBody withBody() {
            return this._withBody;
        }

        @Override
        public MutableResponseBean setStatus(final int status) {
            this._withStatus = new WithStatus() {
                @Override
                public int status() {
                    return status;
                }};
            return this;
        }

        @Override
        public MutableResponseBean setHeader(final WithHeader withHeader) {
            this._withHeader = withHeader;
            return this;
        }

        @Override
        public MutableResponseBean setBody(final WithBody withBody) {
            this._withBody = withBody;
            return this;
        }

        private WithStatus _withStatus;
        private WithHeader _withHeader;
        private WithBody _withBody;
    }

    private static class WithHeaderSupport implements WithHeader {
        @Override
        public WithHeader setContentDisposition(final String value) {
            this._headers.put(HttpHeaderNames.CONTENT_DISPOSITION.toString(), value);
            return this;
        }

        @Override
        public String contentDisposition() {
            return this._headers.get(HttpHeaderNames.CONTENT_DISPOSITION);
        }

        @Override
        public WithHeader setLocation(final String value) {
            this._headers.put(HttpHeaderNames.LOCATION.toString(), value);
            return this;
        }
        @Override
        public String location() {
            return this._headers.get(HttpHeaderNames.LOCATION);
        }

        @Override
        public WithHeader setHeader(final String name, final String value) {
            this._headers.put(name, value);
            return this;
        }
        @Override
        public Map<String, String> headers() {
            return _headers;
        }

        private final Map<String, String> _headers = new HashMap<>();
    }

    public static Object flushOnly() {
        return DoFlush.Util.flushOnly();
    }

    public static MutableResponseBean response() {
        return new DefaultResponseBean();
    }

    public static WithHeader header() {
        return new WithHeaderSupport();
    }

    public static ResponseBean statusOnly(final int status) {
        return response().setStatus(status);
    }

    public static ResponseBean redirectOnly(final String location) {
        return response().setStatus(302).setHeader(header().setLocation(location));
    }

    public static ResponseBean responseAsJson(final int status, final Object pojo) {
        return response().setStatus(status).setBody(new WithContent() {
            @Override
            public String contentType() {
                return MediaType.APPLICATION_JSON;
            }
            @Override
            public Object content() {
                return pojo;
            }});
    }

    public static ResponseBean responseAsXml(final int status, final Object pojo) {
        return response().setStatus(status).setBody(new WithContent() {
            @Override
            public String contentType() {
                return MediaType.APPLICATION_XML;
            }
            @Override
            public Object content() {
                return pojo;
            }});
    }

    public static ResponseBean responseAsText(final int status, final String text) {
        return response().setStatus(status).setBody(new WithContent() {
            @Override
            public String contentType() {
                return MediaType.TEXT_PLAIN;
            }
            @Override
            public Object content() {
                return text;
            }});
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
}
