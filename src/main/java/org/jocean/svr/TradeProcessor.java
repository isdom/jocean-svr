/**
 * 
 */
package org.jocean.svr;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.BeanHolderAware;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.j2se.jmx.MBeanRegister;
import org.jocean.j2se.jmx.MBeanRegisterAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.EmptyByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import rx.Subscriber;
import rx.functions.Func0;

/**
 * @author isdom
 *
 */
public class TradeProcessor extends Subscriber<HttpTrade> 
    implements MBeanRegisterAware, BeanHolderAware  {

    private static final Logger LOG =
            LoggerFactory.getLogger(TradeProcessor.class);

    private static final String APPLICATION_JSON_CHARSET_UTF_8 = 
            "application/json; charset=UTF-8";
    
    public TradeProcessor(
            final Registrar  registrar) {
        this._registrar = registrar;
//        this._jsonProvider = jsonProvider;
    }
    
    @Override
    public void setBeanHolder(final BeanHolder beanHolder) {
        this._beanHolder = beanHolder;
    }
    
    public void destroy() {
        //  clean up all leak HttpDatas
        HTTP_DATA_FACTORY.cleanAllHttpData();
    }
    
    @Override
    public void onCompleted() {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onError(final Throwable e) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onNext(final HttpTrade trade) {
//        if ( null != this._beanHolder) {
//            final ReadPolicy policy = _beanHolder.getBean(ReadPolicy.class);
//            if (null != policy) {
//                trade.setReadPolicy(policy);
//            }
//        }
        trade.inbound().subscribe(
            buildInboundSubscriber(trade, 
            trade.inboundHolder().fullOf(RxNettys.BUILD_FULL_REQUEST)));
    }

    private Subscriber<HttpObject> buildInboundSubscriber(
            final HttpTrade trade,
            final Func0<FullHttpRequest> buildFullReq) {
        return new Subscriber<HttpObject>() {
            private HttpRequest _request;
          
            private void destructor() {
                destroyPostDecoder();
            }

            private void destroyPostDecoder() {
            }
            
            @Override
            public void onCompleted() {
//                if (this._isMultipart) {
//                    onCompleted4Multipart();
//                } else {
                    onCompleted4Standard();
//                }
                destructor();
            }

            @Override
            public void onError(final Throwable e) {
                LOG.warn("SOURCE_CANCELED\nfor cause:[{}]", 
                        ExceptionUtils.exception2detail(e));
                destructor();
            }
            
            private void onCompleted4Multipart() {
            }

            private void onCompleted4Standard() {
                final FullHttpRequest req = buildFullReq.call();
                if (null!=req) {
                    try {
                        trade.outbound(
                            _registrar.buildResource(req, trade.inbound()));
                    } catch (Exception e) {
                        LOG.warn("exception when createAndInvokeRestfulBusiness, detail:{}",
                                ExceptionUtils.exception2detail(e));
                    } finally {
                        req.release();
                    }
                }
            }
            
            @Override
            public void onNext(final HttpObject msg) {
                if (msg instanceof HttpRequest) {
                    this._request = (HttpRequest)msg;
//                    if ( this._request.method().equals(HttpMethod.POST)
//                            && HttpPostRequestDecoder.isMultipart(this._request)) {
//                    } else {
//                        this._isMultipart = false;
//                    }
                }
//                if (msg instanceof HttpContent && this._isMultipart) {
//                    onNext4Multipart((HttpContent)msg);
//                }
            }

            private void onNext4Multipart(HttpContent content) {
//                if (null!=this._postDecoder) {
//                    try {
//                        this._postDecoder.offer(content);
//                    } catch (ErrorDataDecoderException e) {
//                        //  TODO
//                    }
//                    try {
//                        while (this._postDecoder.hasNext()) {
//                            final InterfaceHttpData data = this._postDecoder.next();
//                            if (data != null) {
//                                try {
//                                    if ( !processHttpData(data) ) {
//                                        destroyPostDecoder();
//                                        break;
//                                    }
//                                } finally {
//                                    data.release();
//                                }
//                            }
//                        }
//                    } catch (EndOfDataDecoderException e) {
//                        //  TODO
//                    }
//                }
            }
        };
    }

    private void addExtraHeaders(final FullHttpResponse response) {
        if (null!=this._extraHeaders) {
            for (Map.Entry<String, String> entry : this._extraHeaders.entrySet()) {
                response.headers().set(entry.getKey(), entry.getValue());
            }
        }
    }
    
    private static String toQueryString(final ByteBuf content)
            throws UnsupportedEncodingException, IOException {
        if (content instanceof EmptyByteBuf) {
            return null;
        }
        return new String(ByteStreams.toByteArray(new ByteBufInputStream(content.slice())),
                "UTF-8");
    }

    private static boolean isPostWithForm(final FullHttpRequest req) {
        return req.method().equals(HttpMethod.POST)
          && req.headers().contains(HttpHeaderNames.CONTENT_TYPE)
          && req.headers().get(HttpHeaderNames.CONTENT_TYPE)
              .startsWith(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString());
    }

    public void setDefaultContentType(final String defaultContentType) {
        this._defaultContentType = defaultContentType;
    }
    
    public void setExtraHeaders(final Map<String, String> extraHeaders) {
        this._extraHeaders = extraHeaders;
    }

    private final HttpDataFactory HTTP_DATA_FACTORY =
            new DefaultHttpDataFactory(false);  // DO NOT use Disk
    private final Registrar _registrar;
//    private final JSONProvider _jsonProvider;
    
    private BeanHolder _beanHolder;
    
    private String _defaultContentType = APPLICATION_JSON_CHARSET_UTF_8;
    private Map<String, String> _extraHeaders;

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        register.registerMBean("name=tradeProcessor", this);
    }

//    @Override
//    public int getCurrentUndecodedSize() {
//        return this._currentUndecodedSize.get();
//    }
//    
//    @Override
//    public int getPeakUndecodedSize() {
//        return this._peakUndecodedSize.get();
//    }
    
    private final AtomicInteger  _currentUndecodedSize = new AtomicInteger(0);
    private final AtomicInteger  _peakUndecodedSize = new AtomicInteger(0);

    private void updateCurrentUndecodedSize(final int delta) {
        final int current = this._currentUndecodedSize.addAndGet(delta);
        if (delta > 0) {
            boolean updated = false;
            
            do {
                // try to update peak memory value
                final int peak = this._peakUndecodedSize.get();
                if (current > peak) {
                    updated = this._peakUndecodedSize.compareAndSet(peak, current);
                } else {
                    break;
                }
            } while (!updated);
        }
    }
}
