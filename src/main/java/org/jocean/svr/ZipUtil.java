package org.jocean.svr;

import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.Terminable;
import org.jocean.netty.util.ByteBufsOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.functions.Func1;

public class ZipUtil {
    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(ZipUtil.class);
    
    private ZipUtil() {
        throw new IllegalStateException("No instances!");
    }
    
    public interface ZipBuilder {
        
        public ZipBuilder newbuf(final Func0<ByteBuf> newBuffer);
        
        public ZipBuilder bufsize(final int bufsize);
        
        public ZipBuilder terminable(final Terminable terminable);
        
        public ZipBuilder entryname(final String entryname);
        
        public Transformer<HttpObject, Object> build();
    }
    
    public static Observable.Transformer<HttpObject, Object> toZip(
            final String zippedName,
            final String contentName,
            final Terminable terminable,
            final Func0<ByteBuf> newBuffer,
            final int bufsize) {
        return new Observable.Transformer<HttpObject, Object>() {
            @Override
            public Observable<Object> call(final Observable<HttpObject> obsResponse) {
                
                final ByteBufsOutputStream bufout = new ByteBufsOutputStream(newBuffer, null);
                final ZipOutputStream zipout = new ZipOutputStream(bufout, CharsetUtil.UTF_8);
                zipout.setLevel(Deflater.BEST_COMPRESSION);
                
                final byte[] readbuf = new byte[bufsize];
                
                terminable.doOnTerminate(() -> {
                    try {
                        zipout.close();
                    } catch (IOException e1) {
                    }
                });
                
                return obsResponse.flatMap(RxNettys.splitFullHttpMessage())
                .flatMap(httpobj -> {
                    if (httpobj instanceof HttpResponse) {
                        return Observable.concat(onResponse((HttpResponse)httpobj, zippedName), 
                                bufout2bufs(bufout, addEntry(zipout, contentName)).map(todwb(terminable)));
                    } else if (httpobj instanceof HttpContent) {
                        final HttpContent content = (HttpContent)httpobj;
                        if (content.content().readableBytes() == 0) {
                            return Observable.empty();
                        } else {
                            return bufout2bufs(bufout, addContent(zipout, content, readbuf)).map(todwb(terminable));
                        }
                    } else {
                        return Observable.just(httpobj);
                    }},
                    e -> Observable.error(e),
                    () -> Observable.concat(bufout2bufs(bufout, finish(zipout)).map(todwb(terminable)), 
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
    
    private static Action0 addEntry(final ZipOutputStream zipout, final String contentName) {
        return ()-> {
            try {
                final ZipEntry entry = new ZipEntry(contentName);
                zipout.putNextEntry(entry);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
    
    private static Action0 addContent(final ZipOutputStream zipout, final HttpContent content, final byte[] readbuf) {
        return ()->{
            try (final ByteBufInputStream is = new ByteBufInputStream(content.content())) {
                int readed;
                while ((readed = is.read(readbuf)) > 0) {
                    zipout.write(readbuf, 0, readed);
                }
                zipout.flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static Action0 finish(final ZipOutputStream zipout) {
        return ()->{
            try {
                zipout.closeEntry();
                zipout.finish();
                zipout.close();
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
                    subscriber.onCompleted();
                } catch (Exception e) {
                    subscriber.onError(e);
                } finally {
                    bufout.setOnBuffer(null);
                }
            }
        });
    }

    private static Func1<ByteBuf, DisposableWrapper<ByteBuf>> todwb(final Terminable terminable) {
        return DisposableWrapperUtil.<ByteBuf>wrap(RxNettys.<ByteBuf>disposerOf(), terminable);
    }
}
