package org.jocean.svr;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.core.MediaType;

import org.jocean.http.DoFlush;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.Transformer;

public class ResponseUtil {

    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(ResponseUtil.class);

    private ResponseUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static Object flushOnly() {
        return DoFlush.Util.flushOnly();
    }

    public static final class StatusOnly extends HeaderOnly implements WithStatus {
        StatusOnly(final int status) {
            this._status = status;
        }

        @Override
        public int status() {
            return _status;
        }

        private final int _status;
    }

    public static StatusOnly statusOnly(final int status) {
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

    public static Redirectable redirectOnly(final String location) {
        return new Redirectable(location);
    }

    public static final class StatusAndContent implements WithStatus, WithContent {
        StatusAndContent(final int status, final String contentType, final Object content) {
            this._status = status;
            this._contentType = contentType;
            this._content = content;
        }

        @Override
        public int status() {
            return this._status;
        }

        @Override
        public String contentType() {
            return _contentType;
        }

        @Override
        public Object content() {
            return _content;
        }

        private final int _status;
        private final Object _content;
        private final String _contentType;
    }

    public static StatusAndContent responseAsJson(final int status, final Object pojo) {
        return new StatusAndContent(status, MediaType.APPLICATION_JSON, pojo);
    }

    public static StatusAndContent responseAsXml(final int status, final Object pojo) {
        return new StatusAndContent(status, MediaType.APPLICATION_XML, pojo);
    }

    public static StatusAndContent responseAsText(final int status, final String text) {
        return new StatusAndContent(status, MediaType.TEXT_PLAIN, text);
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
