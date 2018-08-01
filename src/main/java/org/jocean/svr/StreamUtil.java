package org.jocean.svr;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.jocean.http.ByteBufSlice;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.netty.util.BufsInputStream;
import org.jocean.netty.util.NoDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import rx.Observable;
import rx.Observable.Transformer;

public class StreamUtil {
    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(StreamUtil.class);

    private StreamUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static Transformer<ByteBufSlice, String> asLines() {

        final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin = new BufsInputStream<>(
                dwb->dwb.unwrap(), dwb->dwb.dispose());
        final StringBuilder lineBuf = new StringBuilder();

        return bbses ->
            bbses.flatMap(bbs -> {
                // add all upstream dwb to bufin stream
                bufin.appendBufs(bbs.element().toList().toBlocking().single());
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

                try {
                    return Observable.from(lines);
                } finally {
                    bbs.step();
                }
            },
            e -> Observable.error(e),
            () -> {
                // stream is end
                bufin.markEOS();
                if (lineBuf.length() > 0) {
                    return Observable.just(lineBuf.toString());
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
}
