package org.jocean.svr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jocean.http.ByteBufSlice;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Stepable;
import org.jocean.netty.util.BufsInputStream;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.netty.util.NoDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Subscriber;
import rx.functions.Action2;
import rx.functions.Func0;
import rx.functions.Func2;

public class ByteBufSliceUtil {
    private static final Logger LOG
        = LoggerFactory.getLogger(ByteBufSliceUtil.class);

    private ByteBufSliceUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static <T extends Stepable<?>> Transformer<T, ByteBufSlice> stepable2bbs(
            final Func0<DisposableWrapper<ByteBuf>> allocator,
            final Action2<T, OutputStream> fillout) {
        return stepables -> {
            final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout = new BufsOutputStream<>(allocator, dwb->dwb.unwrap());

            return stepables.map(stepable -> {
                final List<DisposableWrapper<ByteBuf>> dwbs = new ArrayList<>();
                bufout.setOutput(dwb -> dwbs.add(dwb));
                try {
                    fillout.call(stepable, bufout);
                    bufout.flush();
                } catch (final Exception e) {
                    LOG.warn("exception when generate ByteBuf, detail: {}", ExceptionUtils.exception2detail(e));
                }

                return new ByteBufSlice() {
                    @Override
                    public String toString() {
                        return new StringBuilder().append("ByteBufSlice from [").append(stepable).append("]").toString();
                    }
                    @Override
                    public void step() {
                        stepable.step();
                    }
                    @Override
                    public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                        return dwbs;
                    }};
            });
        };
    }

    public static Transformer<ByteBufSlice, Stepable<List<String>>> asLineSlice() {

        final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin = new BufsInputStream<>(
                dwb->dwb.unwrap(), dwb->dwb.dispose());
        final StringBuilder lineBuf = new StringBuilder();

        return bbses ->
            bbses.flatMap(bbs -> {
                // add all upstream dwb to bufin stream
                bufin.appendIterable(bbs.element());
                // read as InputStream
                final List<String> lines = new ArrayList<>();

                try {
                    while (true) {
                        lines.add(readLine(bufin, lineBuf));
                    }
                } catch (final IOException e) {
                    if (!(e instanceof NoDataException)) {
                        return Observable.error(e);
                    }
                }

                if (!lines.isEmpty()) {
                    // read at least one line
                    return Observable.<Stepable<List<String>>>just(new Stepable<List<String>>() {
                        @Override
                        public void step() {
                            bbs.step();
                        }
                        @Override
                        public List<String> element() {
                            return lines;
                        }});
                } else {
                    LOG.debug("no lines readed, auto step this bbs");
                    bbs.step();
                    return Observable.empty();
                }
            },
            e -> Observable.error(e),
            () -> {
                // stream is end
                bufin.markEOS();
                if (lineBuf.length() > 0) {
                    return Observable.<Stepable<List<String>>>just(new Stepable<List<String>>() {

                        @Override
                        public void step() {
                        }

                        @Override
                        public List<String> element() {
                            return Arrays.asList(lineBuf.toString());
                        }});
                } else {
                    return Observable.empty();
                }
            });
    }

    public static  String readLine(final InputStream in, final StringBuilder lineBuf) throws IOException {
        loop: while (true) {
            final int c = in.read();
            switch (c) {
                case '\n':
                    break loop;

                case '\r':
//                    if (buffer.isReadable() && (char) buffer.getUnsignedByte(buffer.readerIndex()) == '\n') {
//                        buffer.skipBytes(1);
//                    }
//                    break loop;
                    continue;
                case -1:
                    break loop;
                default:
                    lineBuf.append((char) c);
            }
        }

        try {
            return lineBuf.toString();
        } finally {
            lineBuf.setLength(0);
        }
    }

    public interface SliceContext {
        public boolean isCompleted();
        public Observable<Iterable<DisposableWrapper<ByteBuf>>> element();
        public SliceContext next();
    }

    public static void stream2bbs(
            final SliceContext ctx,
            final Subscriber<? super ByteBufSlice> subscriber) {
//        final int step = Math.min(end - begin + 1, maxstep);
//        if (step <= 0)
        if (ctx.isCompleted()) {
            if (!subscriber.isUnsubscribed()) {
                subscriber.onCompleted();
            }
        } else {
            ctx.element().subscribe(iterable -> {
                if (!subscriber.isUnsubscribed()) {
                    subscriber.onNext(new ByteBufSlice() {
                        @Override
                        public void step() {
                            // begin + step, end, maxstep,
                            stream2bbs(ctx.next(), subscriber);
                        }
                        @Override
                        public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                            return iterable;
                        }});
                }
            }, e -> {
                if (!subscriber.isUnsubscribed()) {
                    subscriber.onError(e);
                }
            });
        }
    }

    public static void range2slice(
            final Subscriber<? super ByteBufSlice> subscriber,
            final int begin,
            final int end,
            final int maxstep,
            final Func2<Integer, Integer, Observable<Iterable<DisposableWrapper<ByteBuf>>>> bbsbuilder) {
        final int step = Math.min(end - begin + 1, maxstep);
        if (step <= 0) {
            if (!subscriber.isUnsubscribed()) {
                subscriber.onCompleted();
            }
        }
        bbsbuilder.call(begin, step).subscribe(iterable -> {
            if (!subscriber.isUnsubscribed()) {
                subscriber.onNext(new ByteBufSlice() {
                    @Override
                    public void step() {
                        range2slice(subscriber, begin + step, end, maxstep, bbsbuilder);
                    }
                    @Override
                    public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                        return iterable;
                    }});
            }
        }, e -> {
            if (!subscriber.isUnsubscribed()) {
                subscriber.onError(e);
            }
        });
    }
}
