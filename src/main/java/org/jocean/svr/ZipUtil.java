package org.jocean.svr;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageUtil;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Terminable;
import org.jocean.netty.util.BufsInputStream;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.netty.util.NoDataException;
import org.jocean.netty.zip.ZipInputStreamX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

public class ZipUtil {
    private static final Logger LOG
        = LoggerFactory.getLogger(ZipUtil.class);

    private ZipUtil() {
        throw new IllegalStateException("No instances!");
    }

    public static Transformer<ByteBufSlice, ByteBufSlice> unzipSlices(
            final Func0<DisposableWrapper<ByteBuf>> allocator,
            final Terminable terminable,
            final int bufsize,
            final Action1<DisposableWrapper<? extends ByteBuf>> onunzipped ) {
        return bbses -> {
            final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin = new BufsInputStream<>(dwb->dwb.unwrap(), onunzipped);
            final ZipInputStreamX zipin = new ZipInputStreamX(bufin);
            final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout = new BufsOutputStream<>(allocator, dwb->dwb.unwrap());
            final byte[] readbuf = new byte[bufsize];

            terminable.doOnTerminate(() -> {
                try {
                    zipin.close();
                } catch (final IOException e1) {
                }
            });

            final AtomicReference<ZipEntry> entryRef = new AtomicReference<>(null);

            return bbses.map(bbs -> {
                bufin.appendBufs(bbs.element().toList().toBlocking().single());

                final Observable<? extends DisposableWrapper<? extends ByteBuf>> unzipped =
                    unzip(zipin, bufout, readbuf, entryRef).cache();

                return new ByteBufSlice() {
                    @Override
                    public String toString() {
                        return new StringBuilder().append("ByteBufSlice [unzipped for ").append(bbs).append("]").toString();
                    }
                    @Override
                    public void step() {
                        bbs.step();
                    }
                    @Override
                    public Observable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                        return unzipped;
                    }
                };
            }/*,
            e -> Observable.error(e),
            () -> {
                bufin.markEOS();
                final Observable<? extends DisposableWrapper<? extends ByteBuf>> unzipped =
                        unzip(zipin, bufout, readbuf, entryRef).cache();

                    return Observable.<ByteBufSlice>just(new ByteBufSlice() {
                        @Override
                        public void step() {}

                        @Override
                        public Observable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                            return unzipped;
                        }

                        @Override
                        public String toString() {
                            return new StringBuilder()
                                    .append(",element:").append(unzipped)
                                    .toString();
                        }
                    });
            }*/);

        };
    }

    private static Observable<? extends DisposableWrapper<? extends ByteBuf>> unzip(
            final ZipInputStreamX zipin,
            final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout,
            final byte[] readbuf,
            final AtomicReference<ZipEntry> entryRef) {
        if (entryRef.get() == null) {
            try {
                entryRef.set(zipin.getNextEntry());
                LOG.info("read next zip entry: {}", entryRef.get());
            } catch (final IOException e) {
            }
        }
        if (entryRef.get() != null) {
            return MessageUtil.fromBufout(bufout, ()-> {
                int readed = 0;
                do {
                    try {
                        readed = zipin.read(readbuf);
                        if (readed > 0) {
                            bufout.write(readbuf, 0, readed);
                        }
                    } catch (final IOException e) {
                        if (e instanceof NoDataException) {
                            LOG.debug("zipin has no more data");
                            break;
                        } else {
                            throw new RuntimeException(e);
                        }
                    }
                } while (readed > 0);
                // means end of zipped stream
                try {
                    bufout.flush();
                } catch (final IOException e) {}
            });
        } else {
            return Observable.empty();
        }
    }

    public static Transformer<ByteBufSlice, ByteBufSlice> zipSlices(
            final Func0<DisposableWrapper<ByteBuf>> allocator,
            final String entryName,
            final Terminable terminable,
            final int bufsize,
            final Action1<DisposableWrapper<ByteBuf>> onzipped) {
        return bbses -> {
            final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout = new BufsOutputStream<>(allocator, dwb->dwb.unwrap());
            final ZipOutputStream zipout = new ZipOutputStream(bufout, CharsetUtil.UTF_8);
            zipout.setLevel(Deflater.BEST_COMPRESSION);

            final byte[] readbuf = new byte[bufsize];

            terminable.doOnTerminate(() -> {
                try {
                    zipout.close();
                } catch (final IOException e1) {
                }
            });

            return Observable.concat(
                    // start zip: entry info
                    Observable.defer(()->zipentry(entryName, zipout, bufout)),
                    // zip content
                    bbses.map(dozip(zipout, bufout, readbuf, onzipped)),
                    // end of zip: finish all
                    Observable.defer(()->finishzip(zipout, bufout)))
                    ;
        };
    }

    private static Observable<ByteBufSlice> zipentry(
            final String entryName,
            final ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout
            ) {
        final Observable<? extends DisposableWrapper<? extends ByteBuf>> zipped = MessageUtil.fromBufout(bufout, ()-> {
            try {
                zipout.putNextEntry(new ZipEntry(entryName));
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }).cache();
        return Observable.just(new ByteBufSlice() {
            @Override
            public String toString() {
                return new StringBuilder()
                        .append("ByteBufSlice [beginzip entry=").append(entryName).append("]").toString();
            }

            @Override
            public void step() {}

            @Override
            public Observable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return zipped;
            }});
    }

    private static Func1<ByteBufSlice, ByteBufSlice> dozip(
            final ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout,
            final byte[] readbuf,
            final Action1<DisposableWrapper<ByteBuf>> onzipped) {
        return bbs -> {
            final Observable<? extends DisposableWrapper<? extends ByteBuf>> zipped = bbs.element()
                    .flatMap(zipcontent(zipout, bufout, readbuf, onzipped)).cache();
            return new ByteBufSlice() {
                @Override
                public String toString() {
                    return new StringBuilder().append("ByteBufSlice [zipped for ").append(bbs).append("]").toString();
                }
                @Override
                public void step() {
                    bbs.step();
                }

                @Override
                public Observable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                    return zipped;
                }
            };
        };
    }

    private static Func1<DisposableWrapper<? extends ByteBuf>, ? extends Observable<? extends DisposableWrapper<ByteBuf>>>
        zipcontent(
            final ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout,
            final byte[] readbuf,
            final Action1<DisposableWrapper<ByteBuf>> onzipped) {
        return dwb -> MessageUtil.fromBufout(bufout, addBuf(zipout, (DisposableWrapper<ByteBuf>) dwb, readbuf, onzipped));
    }

    private static Observable<ByteBufSlice> finishzip(
            final ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout) {
        final Observable<? extends DisposableWrapper<? extends ByteBuf>> zipped = MessageUtil.fromBufout(bufout, () -> {
            try {
                zipout.closeEntry();
                zipout.finish();
                zipout.close();
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }).cache();
        return Observable.just(new ByteBufSlice() {
            @Override
            public String toString() {
                return "ByteBufSlice [finishzip]";
            }
            @Override
            public void step() {}

            @Override
            public Observable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return zipped;
            }});
    }

    public static interface Entry {
        public String name();
        public Observable<? extends DisposableWrapper<ByteBuf>> content();
    }

    public static interface EntryBuilder {
        public EntryBuilder name(final String name);
        public EntryBuilder content(final Observable<? extends DisposableWrapper<ByteBuf>> content);
        public Entry build();
    }

    public static EntryBuilder entry(final String name) {
        final AtomicReference<String> _nameRef = new AtomicReference<>(name);
        final AtomicReference<Observable<? extends DisposableWrapper<ByteBuf>>> _contentRef = new AtomicReference<>(null);

        return new EntryBuilder() {
            @Override
            public EntryBuilder name(final String name) {
                _nameRef.set(name);
                return this;
            }

            @Override
            public EntryBuilder content(final Observable<? extends DisposableWrapper<ByteBuf>> content) {
                _contentRef.set(content);
                return this;
            }

            @Override
            public Entry build() {
                return new Entry() {
                    @Override
                    public String name() {
                        return _nameRef.get();
                    }

                    @Override
                    public Observable<? extends DisposableWrapper<ByteBuf>> content() {
                        return _contentRef.get();
                    }};
            }
        };
    }

    public static interface ZipBuilder {
        public ZipBuilder allocator(final Func0<DisposableWrapper<ByteBuf>> allocator);
        public ZipBuilder entries(final Observable<? extends Entry> entries);
        public ZipBuilder bufsize(final int bufsize);
        public ZipBuilder hookcloser(final Action1<Action0> hookcloser);
        public ZipBuilder doOnZipped(final Action1<DisposableWrapper<ByteBuf>> onzipped);
        public Observable<? extends DisposableWrapper<ByteBuf>> build();
    }

    public static ZipBuilder zip() {
        final AtomicReference<Func0<DisposableWrapper<ByteBuf>>> allocatorRef = new AtomicReference<>();
        final AtomicReference<Observable<? extends Entry>> entriesRef = new AtomicReference<>();
        final AtomicReference<Integer> bufsizeRef = new AtomicReference<>(512);
        final AtomicReference<Action1<Action0>> hookcloserRef = new AtomicReference<>(null);
        final AtomicReference<Action1<DisposableWrapper<ByteBuf>>> onzippedRef = new AtomicReference<>(null);
        return new ZipBuilder() {

            @Override
            public ZipBuilder allocator(final Func0<DisposableWrapper<ByteBuf>> allocator) {
                allocatorRef.set(allocator);
                return this;
            }

            @Override
            public ZipBuilder entries(final Observable<? extends Entry> entries) {
                entriesRef.set(entries);
                return this;
            }

            @Override
            public ZipBuilder bufsize(final int bufsize) {
                bufsizeRef.set(bufsize);
                return this;
            }

            @Override
            public ZipBuilder hookcloser(final Action1<Action0> hookcloser) {
                hookcloserRef.set(hookcloser);
                return this;
            }

            @Override
            public ZipBuilder doOnZipped(final Action1<DisposableWrapper<ByteBuf>> onzipped) {
                onzippedRef.set(onzipped);
                return this;
            }

            @Override
            public Observable<? extends DisposableWrapper<ByteBuf>> build() {
                if (null == allocatorRef.get()) {
                    throw new NullPointerException("allocator");
                }
                if (null == entriesRef.get()) {
                    throw new NullPointerException("entries");
                }
                return doZip(allocatorRef.get(), bufsizeRef.get(), entriesRef.get(), hookcloserRef.get(), onzippedRef.get());
            }};
    }

    private static Observable<? extends DisposableWrapper<ByteBuf>> doZip(
            final Func0<DisposableWrapper<ByteBuf>> allocator,
            final int bufsize,
            final Observable<? extends Entry> entries,
            final Action1<Action0> hookcloser,
            final Action1<DisposableWrapper<ByteBuf>> onzipped) {
        final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout = new BufsOutputStream<>(allocator, dwb->dwb.unwrap());
        final ZipOutputStream zipout = new ZipOutputStream(bufout, CharsetUtil.UTF_8);

        if (null != hookcloser) {
            hookcloser.call(() -> {
                try {
                    zipout.close();
                } catch (final IOException e) {
                }
            });
        }

        zipout.setLevel(Deflater.BEST_COMPRESSION);
        final byte[] readbuf = new byte[bufsize];

        return Observable.concat(
            // for each entry
            entries.flatMap(entry -> Observable.concat(
                    MessageUtil.fromBufout(bufout, ()-> {
                        try {
                            zipout.putNextEntry(new ZipEntry(entry.name()));
                        } catch (final Exception e) {
                            throw new RuntimeException(e);
                        }
                    }),
                    entry.content().flatMap(dwb->MessageUtil.fromBufout(bufout, addBuf(zipout, dwb, readbuf, onzipped))),
                    MessageUtil.fromBufout(bufout, ()->{
                        try {
                            zipout.closeEntry();
                        } catch (final Exception e) {
                            throw new RuntimeException(e);
                        }
                    }))),
            // finish zip stream
            MessageUtil.fromBufout(bufout, ()->{
                try {
                    zipout.finish();
                    zipout.close();
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            }));
    }

    private static Action0 addBuf(final ZipOutputStream zipout,
            final DisposableWrapper<ByteBuf> dwb,
            final byte[] readbuf,
            final Action1<DisposableWrapper<ByteBuf>> onzipped) {
        return ()->{
            try (final ByteBufInputStream is = new ByteBufInputStream(dwb.unwrap())) {
                int readed;
                while ((readed = is.read(readbuf)) > 0) {
                    zipout.write(readbuf, 0, readed);
                }
                zipout.flush();
            } catch (final Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (null != onzipped) {
                    onzipped.call(dwb);
                }
            }
        };
    }
}
