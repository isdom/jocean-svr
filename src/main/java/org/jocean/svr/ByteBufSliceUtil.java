package org.jocean.svr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageUtil;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Stepable;
import org.jocean.netty.util.BufsInputStream;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.netty.util.NoDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Action2;
import rx.functions.Func0;

public class ByteBufSliceUtil {
    private static final Logger LOG = LoggerFactory.getLogger(ByteBufSliceUtil.class);

    private ByteBufSliceUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static ByteBufSlice wrappedSlice(final byte[] array) {
        final List<DisposableWrapper<ByteBuf>> element =
                Arrays.asList(DisposableWrapperUtil.wrap(Unpooled.wrappedBuffer(array), (Action1<ByteBuf>)null));
        return new ByteBufSlice() {

            @Override
            public void step() {}

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return element;
            }};
    }

    public static <T extends Stepable<?>> Transformer<T, ByteBufSlice> stepable2bbs(
            final Func0<DisposableWrapper<? extends ByteBuf>> allocator,
            final Action2<T, OutputStream> fillout) {
        return stepables -> {
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout = new BufsOutputStream<>(allocator, dwb->dwb.unwrap());

            return stepables.map(stepable -> {
                try {
                    final Iterable<DisposableWrapper<? extends ByteBuf>> dwbs =
                            MessageUtil.out2dwbs(bufout, out -> fillout.call(stepable, out));

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
                } catch (final Exception e) {
                    LOG.warn("exception when generate ByteBuf, detail: {}", ExceptionUtils.exception2detail(e));
                    throw e;
                }
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
                    return Observable.just((Stepable<List<String>>)new Stepable<List<String>>() {
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
                    final List<String> lines = Arrays.asList(lineBuf.toString());
                    return Observable.just((Stepable<List<String>>)new Stepable<List<String>>() {

                        @Override
                        public void step() {}

                        @Override
                        public List<String> element() {
                            return lines;
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

    public static Transformer<ByteBufSlice, Stepable<List<String>>> asLineSlice(final char...splitters) {

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
                        lines.add(readUntil(bufin, lineBuf, splitters));
                    }
                } catch (final IOException e) {
                    if (!(e instanceof NoDataException)) {
                        return Observable.error(e);
                    }
                }

                if (!lines.isEmpty()) {
                    // read at least one line
                    return Observable.just((Stepable<List<String>>)new Stepable<List<String>>() {
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
                    final List<String> lines = Arrays.asList(lineBuf.toString());
                    return Observable.just((Stepable<List<String>>)new Stepable<List<String>>() {

                        @Override
                        public void step() {}

                        @Override
                        public List<String> element() {
                            return lines;
                        }});
                } else {
                    return Observable.empty();
                }
            });
    }

    public static  String readUntil(final InputStream in, final StringBuilder lineBuf, final char...splitters) throws IOException {
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
                    if (splitters.length > 0) {
                        for (final char s : splitters) {
                            if ( c == s ) {
                                break loop;
                            }
                        }
                    }
                    lineBuf.append((char) c);
            }
        }

        try {
            return lineBuf.toString();
        } finally {
            lineBuf.setLength(0);
        }
    }
}
