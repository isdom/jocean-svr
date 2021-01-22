package org.jocean.svr;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
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
    private static final Logger LOG = LoggerFactory.getLogger(ResponseUtil.class);

    private ResponseUtil() {
        throw new IllegalStateException("No instances!");
    }

    private static class DefaultResponseBean implements MutableResponseBean {

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();
            builder.append("DefaultResponseBean [withStatus=").append(_withStatus).append(", withHeader=")
                    .append(_withHeader).append(", withBody=").append(_withBody).append("]");
            return builder.toString();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((_withBody == null) ? 0 : _withBody.hashCode());
            result = prime * result + ((_withHeader == null) ? 0 : _withHeader.hashCode());
            result = prime * result + ((_withStatus == null) ? 0 : _withStatus.hashCode());
            return result;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final DefaultResponseBean other = (DefaultResponseBean) obj;
            if (_withBody == null) {
                if (other._withBody != null) {
                    return false;
                }
            } else if (!_withBody.equals(other._withBody)) {
                return false;
            }
            if (_withHeader == null) {
                if (other._withHeader != null) {
                    return false;
                }
            } else if (!_withHeader.equals(other._withHeader)) {
                return false;
            }
            if (_withStatus == null) {
                if (other._withStatus != null) {
                    return false;
                }
            } else if (!_withStatus.equals(other._withStatus)) {
                return false;
            }
            return true;
        }

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
                public String toString() {
                    final StringBuilder builder = new StringBuilder();
                    builder.append("WithStatus [status=").append(status).append("]");
                    return builder.toString();
                }

                @Override
                public int hashCode() {
                    return status;
                }

                @Override
                public boolean equals(final Object obj) {
                    if (this == obj) {
                        return true;
                    }
                    if (obj == null) {
                        return false;
                    }
                    if (!(obj instanceof WithStatus)) {
                        return false;
                    }
                    return status == ((WithStatus)obj).status();
                }

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
        public String toString() {
            final int maxLen = 10;
            final StringBuilder builder = new StringBuilder();
            builder.append("WithHeaderSupport [headers=")
                    .append(_headers != null ? toString(_headers.entrySet(), maxLen) : null).append("]");
            return builder.toString();
        }

        private String toString(final Collection<?> collection, final int maxLen) {
            final StringBuilder builder = new StringBuilder();
            builder.append("[");
            int i = 0;
            for (final Iterator<?> iterator = collection.iterator(); iterator.hasNext() && i < maxLen; i++) {
                if (i > 0)
                    builder.append(", ");
                builder.append(iterator.next());
            }
            builder.append("]");
            return builder.toString();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((_headers == null) ? 0 : _headers.hashCode());
            return result;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final WithHeaderSupport other = (WithHeaderSupport) obj;
            if (_headers == null) {
                if (other._headers != null) {
                    return false;
                }
            } else if (!_headers.equals(other._headers)) {
                return false;
            }
            return true;
        }

        @Override
        public WithHeader setContentDisposition(final String value) {
            this._headers.put(HttpHeaderNames.CONTENT_DISPOSITION.toString(), value);
            return this;
        }

        @Override
        public String contentDisposition() {
            return this._headers.get(HttpHeaderNames.CONTENT_DISPOSITION.toString());
        }

        @Override
        public WithHeader setLocation(final String value) {
            this._headers.put(HttpHeaderNames.LOCATION.toString(), value);
            return this;
        }
        @Override
        public String location() {
            return this._headers.get(HttpHeaderNames.LOCATION.toString());
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
