package org.jocean.svr;

import java.util.ArrayList;
import java.util.List;

import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

class MultipartOMD implements Observable.OnSubscribe<MessageDecoder> {
    private static final Logger LOG =
            LoggerFactory.getLogger(MultipartOMD.class);

    private final class ToBlob implements Func1<HttpObject, Observable<MessageDecoder>> {
        private final HttpDataFactory hTTP_DATA_FACTORY = 
                new DefaultHttpDataFactory(false);  // DO NOT use Disk;
        
        private final HttpPostMultipartRequestDecoder _decoder;
        
        ToBlob(final HttpRequest request, final Subscriber<?> subscriber) {
            this._decoder = new HttpPostMultipartRequestDecoder(
                    hTTP_DATA_FACTORY, request);
            _decoder.setDiscardThreshold(1024);
//          _decoder.setDiscardThreshold(_discardThreshold);
//          try {
//              final Field chunkField = _postDecoder.getClass().getDeclaredField("undecodedChunk");
//              if (null != chunkField) {
//                  chunkField.setAccessible(true);
//                  chunkField.set(_postDecoder, PooledByteBufAllocator.DEFAULT.directBuffer());
//              } else {
//                  LOG.warn("not found HttpPostMultipartRequestDecoder.undecodedChunk field");
//              }
//          } catch (Exception e) {
//              LOG.warn("exception when set undecodedChunk to null, detail: {}",
//                      ExceptionUtils.exception2detail(e));
//          }
            subscriber.add(Subscriptions.create(new Action0() {
                @Override
                public void call() {
                    _decoder.destroy();
                }}));
        }
        
        @Override
        public Observable<MessageDecoder> call(final HttpObject msg) {
            if (msg instanceof HttpContent) {
                return content2MD((HttpContent)msg);
            } else {
                return Observable.empty();
            }
        }

        private Observable<MessageDecoder> content2MD(final HttpContent content) {
            try {
                LOG.info("content2MD: before offer content {} with size {}", 
                        content, content.content().readableBytes());
                this._decoder.offer(content);
            } catch (ErrorDataDecoderException e) {
                LOG.warn("exception when postDecoder.offer, detail: {}", 
                        ExceptionUtils.exception2detail(e));
            }
//            finally {
//                if (null != this._msgHolder) {
//                    this._msgHolder.releaseHttpContent(content);
//                }
//            }
            final List<MessageDecoder> mds = new ArrayList<>();
            try {
                while (this._decoder.hasNext()) {
                    final InterfaceHttpData data = this._decoder.next();
                    if (data != null) {
                        try {
                            final MessageDecoder md = processHttpData(data);
                            if (null != md) {
                                mds.add(md);
                                LOG.info("content2MD: add MessageDecoder {}", md);
                            }
                        } finally {
                            data.release();
                        }
                    }
                }
            } catch (EndOfDataDecoderException e) {
                LOG.warn("exception when postDecoder.hasNext, detail: {}", 
                        ExceptionUtils.exception2detail(e));
            }
            return mds.isEmpty() ? Observable.<MessageDecoder>empty() : Observable.from(mds);
        }
        
        private MessageDecoder processHttpData(final InterfaceHttpData data) {
            if (data.getHttpDataType().equals(
                InterfaceHttpData.HttpDataType.FileUpload)) {
                final FileUpload fileUpload = (FileUpload)data;
                LOG.info("processHttpData: fileUpload's content is {}", Nettys.dumpByteBufHolder(fileUpload));
                return buildMD(fileUpload);
            } else {
                LOG.info("InterfaceHttpData ({}) is NOT fileUpload, so ignore", data);
            }
            return null;
        }
    }

    public MultipartOMD(final HttpTrade trade, final HttpRequest request) {
        this._trade = trade;
        this._request = request;
    }

    @Override
    public void call(final Subscriber<? super MessageDecoder> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            this._trade.doOnTerminate(new Action0() {
                @Override
                public void call() {
                    subscriber.unsubscribe();
                }});
            
            final ToBlob toblob = new ToBlob(this._request, subscriber);
            
            // TBD: release toblob and release subscriber within trade
            this._trade.inbound()
            .flatMap(toblob)
            .subscribe(subscriber);
        }
    }

    private final HttpTrade _trade;
    private final HttpRequest _request;

    private static MessageDecoder buildMD(final FileUpload fileUpload) {
        return new MessageDecoderUsingHolder(
                new Func0<FileUpload>() {
                    @Override
                    public FileUpload call() {
                        return fileUpload.retain();
                    }},
                fileUpload.content().readableBytes(),
                fileUpload.getContentType(),
                fileUpload.getFilename(),
                fileUpload.getName());
    }
}
