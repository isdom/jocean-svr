package org.jocean.svr;

import java.util.ArrayList;
import java.util.List;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageBody;
import org.jocean.http.MessageUtil;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Terminable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
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
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

class MultipartBody implements Observable.OnSubscribe<MessageBody> {
    private static final Logger LOG =
            LoggerFactory.getLogger(MultipartBody.class);

    private final class ToBody implements Func1<DisposableWrapper<? extends HttpObject>, Observable<MessageBody>> {
        private final HttpDataFactory _httpDataFactory =
                new DefaultHttpDataFactory(false);  // DO NOT use Disk;

        private final HttpPostMultipartRequestDecoder _decoder;

        ToBody(final HttpRequest request, final Subscriber<?> subscriber) {
            this._decoder = new HttpPostMultipartRequestDecoder(
                    _httpDataFactory, request);
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
            subscriber.add(Subscriptions.create(() -> {
                _decoder.destroy();
                _httpDataFactory.cleanAllHttpData();
            }));
        }

        @Override
        public Observable<MessageBody> call(final DisposableWrapper<? extends HttpObject> msg) {
            if (msg.unwrap() instanceof HttpContent) {
                return content2Body((HttpContent)msg.unwrap());
            } else {
                return Observable.empty();
            }
        }

        private Observable<MessageBody> content2Body(final HttpContent content) {
            try {
                LOG.info("content2MD: before offer content {} with size {}",
                        content, content.content().readableBytes());
                this._decoder.offer(content);
            } catch (final ErrorDataDecoderException e) {
                LOG.warn("exception when postDecoder.offer, detail: {}",
                        ExceptionUtils.exception2detail(e));
            }
//            finally {
//                if (null != this._msgHolder) {
//                    this._msgHolder.releaseHttpContent(content);
//                }
//            }
            final List<MessageBody> mbs = new ArrayList<>();
            try {
                while (this._decoder.hasNext()) {
                    final InterfaceHttpData data = this._decoder.next();
                    if (data != null) {
                        try {
                            final MessageBody body = processHttpData(data);
                            if (null != body) {
                                mbs.add(body);
                                LOG.info("content2MD: add MessageBody {}", body);
                            }
                        } finally {
                            data.release();
                        }
                    }
                }
            } catch (final EndOfDataDecoderException e) {
                LOG.warn("exception when postDecoder.hasNext, detail: {}",
                        ExceptionUtils.exception2detail(e));
            }
            return mbs.isEmpty() ? Observable.<MessageBody>empty() : Observable.from(mbs);
        }

        private MessageBody processHttpData(final InterfaceHttpData data) {
            if (data.getHttpDataType().equals(
                InterfaceHttpData.HttpDataType.FileUpload)) {
                final FileUpload fileUpload = (FileUpload)data;
                LOG.info("processHttpData: fileUpload's content is {}", Nettys.dumpByteBufHolder(fileUpload));
                return buildBody(fileUpload);
            } else {
                LOG.info("InterfaceHttpData ({}) is NOT fileUpload, so ignore", data);
            }
            return null;
        }
    }

    public MultipartBody(final HttpTrade trade, final HttpRequest request) {
        this._trade = trade;
        this._request = request;
    }

    @Override
    public void call(final Subscriber<? super MessageBody> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            this._trade.doOnTerminate(()->subscriber.unsubscribe());

            // TBD: release toblob and release subscriber within trade
            this._trade.inbound().compose(MessageUtil.AUTOSTEP2DWH).flatMap(new ToBody(this._request, subscriber))
                .subscribe(subscriber);
        }
    }

    private final HttpTrade _trade;
    private final HttpRequest _request;

    private MessageBody buildBody(final FileUpload fileUpload) {
        return new MessageBodyUsingHolder(
                this._trade,
                fileUpload.retain(),
                fileUpload.content().readableBytes(),
                fileUpload.getContentType(),
                fileUpload.getFilename(),
                fileUpload.getName());
    }

    private static class MessageBodyUsingHolder implements MessageBody {

        @SuppressWarnings("unused")
        private static final Logger LOG
            = LoggerFactory.getLogger(MessageBodyUsingHolder.class);

        public MessageBodyUsingHolder(
                final Terminable terminable,
                final ByteBufHolder holder,
                final int contentLength,
                final String contentType,
                final String filename,
                final String name
                ) {
            this._terminable = terminable;
            this._holder = holder;
            this._contentLength = contentLength;
            this._contentType = contentType;
            this._filename = filename;
            this._name = name;

            terminable.doOnTerminate(() -> {
                if (null != holder) {
                    holder.release();
                }
            });
        }

        @Override
        public String contentType() {
            return this._contentType;
        }

        @Override
        public int contentLength() {
            return this._contentLength;
        }

        @Override
        public Observable<? extends ByteBufSlice> content() {
            return Observable.just(new ByteBufSlice() {

                @Override
                public void step() {}

                @Override
                public Observable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                    return Observable.unsafeCreate(new OnSubscribe<DisposableWrapper<ByteBuf>>() {
                        @Override
                        public void call(final Subscriber<? super DisposableWrapper<ByteBuf>> subscriber) {
                            if (!subscriber.isUnsubscribed()) {
                                try {
                                    final ByteBuf buf = _holder.content().retainedSlice();
                                    if (null != buf) {
                                        subscriber.onNext(DisposableWrapperUtil.disposeOn(_terminable, RxNettys.wrap4release(buf)));
                                        subscriber.onCompleted();
                                    } else {
                                        subscriber.onError(new RuntimeException("invalid bytebuf"));
                                    }
                                } catch (final Exception e) {
                                    subscriber.onError(e);
                                }
                            }
                        }
                    });
                }});

        }

        private final Terminable _terminable;
        private final ByteBufHolder _holder;
        private final int    _contentLength;
        private final String _contentType;
        private final String _filename;
        private final String _name;
    }
}
