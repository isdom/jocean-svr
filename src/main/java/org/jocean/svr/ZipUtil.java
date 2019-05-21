package org.jocean.svr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageUtil;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.Endable;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.netty.util.BufsInputStream;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.netty.util.NoDataException;
import org.jocean.netty.zip.ZipInputStreamX;
import org.jocean.svr.parse.AbstractParseContext;
import org.jocean.svr.parse.EntityParser;
import org.jocean.svr.parse.ParseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subjects.PublishSubject;

public class ZipUtil {
    private static final Logger LOG = LoggerFactory.getLogger(ZipUtil.class);

    private ZipUtil() {
        throw new IllegalStateException("No instances!");
    }

    public interface TozipEntity {
        public String entryName();
        public Observable<? extends ByteBufSlice> body();
    }

    public interface Zipper extends Transformer<TozipEntity, ByteBufSlice> {
    }

    public interface UnzipEntity {
        public ZipEntry entry();
        public Observable<? extends ByteBufSlice> body();
    }

    public interface Unzipper extends Transformer<ByteBufSlice, UnzipEntity> {
    }

    public interface ZipBuilder {
        public Zipper zip(final int pageSize, final int bufsize);
        public Zipper zipWithPasswd(final int pageSize, final int bufsize, final String passwd);
        public Unzipper unzip(final int pageSize, final int bufsize);
        public Unzipper unzipWithPasswd(final int pageSize, final int bufsize, final String passwd);
    }

    // unzip new impl begin 20190518
    interface UnzipContext extends ParseContext<UnzipEntity>{
        ZipInputStreamX zipin();
        Iterable<DisposableWrapper<? extends ByteBuf>> in2dwbs(AtomicBoolean eoe); // end of current entry
    }

    interface UnzipParser extends EntityParser<UnzipEntity, UnzipContext> {
        @Override
        UnzipParser parse(UnzipContext ctx);
    }

    static class DefaultUnzipContext extends AbstractParseContext<UnzipEntity, UnzipContext> implements UnzipContext {

        DefaultUnzipContext(final ZipInputStreamX zipin,
                final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout,
                final int bufsize,
                final UnzipParser initParser) {
            super(initParser);
            this._zipin = zipin;
            this._bufout = bufout;
            this._buf = new byte[bufsize];
        }

        @Override
        protected boolean hasData() {
            try {
                return _zipin.available() > 0;
            } catch (final IOException e) {
                return false;
            }
        }

        @Override
        public ZipInputStreamX zipin() {
            return _zipin;
        }

        @Override
        public Iterable<DisposableWrapper<? extends ByteBuf>> in2dwbs(final AtomicBoolean eoe) {
            final List<DisposableWrapper<? extends ByteBuf>> dwbs = new ArrayList<>();
            _bufout.setOutput(dwb -> dwbs.add(dwb));

            int readed = 0;
            try {
                do {
                    readed = _zipin.read(_buf);
                    if (readed > 0) {
                        _bufout.write(_buf, 0, readed);
                    }
                } while (readed > 0);
            }
            catch (final IOException e) {
                if (e instanceof NoDataException) {
                    LOG.debug("zipin.read(byte[]) throw NoDataException, stop unzip and wait for input.");
                    this.stopParsing();
//                    return entities(entities);
                } else {
//                    return Observable.error(e);
                }
            }
            finally {
                try {
                    _bufout.flush();
                } catch (final IOException e) {}
            }

            if (readed == -1) {
                LOG.debug("zipin.read(byte[]) return -1, current entry completed.");
            }
            eoe.set(readed == -1);
            return dwbs;
        }

        final ZipInputStreamX _zipin;
        final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> _bufout;
        final byte[] _buf;
    }

    static class GetEntryParser implements UnzipParser {

        @Override
        public UnzipParser parse(final UnzipContext ctx) {
            try {
                // if return null means End Of Stream
                final ZipEntry entry = ctx.zipin().getNextEntry();
                if (null != entry) {
                    LOG.debug("zipin.getNextEntry() return: {}, get new UnzipEntity", entry);
                    return new UnzipEntryParser(entry);
                }
                else {
                    // TODO , no more entries
                    LOG.debug("zipin.getNextEntry() return null, no more entries");
                    return null;
                }
            } catch (final IOException e) {
                if (e instanceof NoDataException) {
                    LOG.debug("zipin.getNextEntry() throw NoDataException, stop unzip and wait for input");
                    return this;
                } else {
                    LOG.debug("zipin.getNextEntry() throw {}", ExceptionUtils.exception2detail(e));
//                    throw e;
                    return null;
                }
            }
        }

    }

    static class UnzipEntryParser implements UnzipParser {

        public UnzipEntryParser(final ZipEntry entry) {
            this._entry = entry;
        }

        @Override
        public UnzipParser parse(final UnzipContext ctx) {
            final AtomicBoolean eoe = new AtomicBoolean(false);
            final Iterable<DisposableWrapper<? extends ByteBuf>> dwbs = ctx.in2dwbs(eoe);
            if (null == _subject) {
                if (eoe.get()) {
                    //  end of entry
                    ctx.appendContent(dwbs, content -> new UnzipEntity() {
                            @Override
                            public ZipEntry entry() {
                                return _entry;
                            }
                            @Override
                            public Observable<? extends ByteBufSlice> body() {
                                return Observable.just(content);
                            }});
                    return new GetEntryParser();
                }
                else {
                    // !NOT! end of entry
                    _subject = PublishSubject.create();
                    ctx.appendContent(dwbs, content -> new UnzipEntity() {
                            @Override
                            public ZipEntry entry() {
                                return _entry;
                            }
                            @Override
                            public Observable<? extends ByteBufSlice> body() {
                                return Observable.just(content).concatWith(_subject);
                            }});
                    return this;
                }
            }
            else {
                if (eoe.get()) {
                    //  end of entry
                    ctx.appendContent(dwbs, content -> {
                        _subject.onNext(content);
                        // entry end, so notify downstream onCompleted event
                        _subject.onCompleted();
                        return null;
                    });
                    return new GetEntryParser();
                }
                else {
                    // !NOT! end of entry
                    ctx.appendContent(dwbs, content -> {
                        _subject.onNext(content);
                        return null;
                    });
                    return this;
                }
            }
        }

        private PublishSubject<ByteBufSlice> _subject;
        private final ZipEntry _entry;
    }

    public static Unzipper unzipToEntitiesNew(
            final Func0<DisposableWrapper<? extends ByteBuf>> allocator,
            final Endable endable,
            final int bufsize,
            final Action1<DisposableWrapper<? extends ByteBuf>> onunzipped ) {
        return content -> {
            final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin = new BufsInputStream<>(dwb->dwb.unwrap(), onunzipped);
            final ZipInputStreamX zipin = new ZipInputStreamX(bufin);
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout = new BufsOutputStream<>(allocator, dwb->dwb.unwrap());

            endable.doOnEnd(() -> {
                try {
                    zipin.close();
                } catch (final IOException e1) {
                }
            });

            final DefaultUnzipContext unzipctx = new DefaultUnzipContext(zipin, bufout, 512, new GetEntryParser());

            return content.flatMap(bbs -> {
                bufin.appendIterable(bbs.element());
                unzipctx.resetParsing();

                return unzipctx.parseEntity(() -> bbs.step());
            },
            e -> Observable.error(e),
            () -> {
                bufin.markEOS();
                unzipctx.resetParsing();
                return unzipctx.parseEntity(() -> {});
            });
        };
    }
    // --- end of --- unzip new impl begin 20190518

    public static Unzipper unzipToEntities(
            final Func0<DisposableWrapper<? extends ByteBuf>> allocator,
            final Endable endable,
            final int bufsize,
            final Action1<DisposableWrapper<? extends ByteBuf>> onunzipped ) {
        return bbses -> {
            // init ctx for unzip
            final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin = new BufsInputStream<>(dwb->dwb.unwrap(), onunzipped);
            final ZipInputStreamX zipin = new ZipInputStreamX(bufin);
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout = new BufsOutputStream<>(allocator, dwb->dwb.unwrap());
            final byte[] readbuf = new byte[bufsize];

            endable.doOnEnd(() -> {
                try {
                    zipin.close();
                } catch (final IOException e1) {
                }
            });

            final AtomicReference<PublishSubject<ByteBufSlice>> currentSubject = new AtomicReference<>(null);

            return bbses.flatMap(bbs -> {
                // append all income data(zipped) to inflater
                final Iterable<? extends DisposableWrapper<? extends ByteBuf>> element = bbs.element();
                if (element.iterator().hasNext()) {
                    LOG.debug("get valid zipped bufs, try to decode zip data");
                    bufin.appendIterable(element);
                    return slice2entities(bbs, zipin, bufout, readbuf, currentSubject);
                } else {
                    // no more data, just call bbs.step() to get more data
                    LOG.debug("no more zipped bufs, try to get more zipped data from upstream");
                    bbs.step();
                    return Observable.empty();
                }
            });
        };
    }

    private static Observable<? extends UnzipEntity> slice2entities(
            final ByteBufSlice bbs,
            final ZipInputStreamX zipin,
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout,
            final byte[] readbuf,
            final AtomicReference<PublishSubject<ByteBufSlice>> currentSubject) {
        final List<UnzipEntity> entities = new ArrayList<>();
        final AtomicReference<Action1<Boolean>> afterNextEntry = new AtomicReference<>(null);
        Func1<List<DisposableWrapper<? extends ByteBuf>>, Action1<Boolean>> onEntryComplete = null;
        Action1<List<DisposableWrapper<? extends ByteBuf>>> onEntryNeedData = null;

        while (true) {
            if (currentSubject.get() == null) {
                // no unzipping entry, try to get Next Entry
                try {
                    final ZipEntry entry = getNextEntry(zipin, afterNextEntry.getAndSet(null));
                    if (null != entry) {
                        onEntryComplete = dwbs -> complete4entry(entities, entry, bbs, dwbs);
                        onEntryNeedData = dwbs -> currentSubject.set(newentity4entry(entities, entry, bbs, dwbs));
                    } else {
                        return entities(entities);
                    }
                } catch (final IOException e) {
                    return Observable.error(e);
                }
            } else {
                onEntryComplete = dwbs -> complete4entry(currentSubject.getAndSet(null), bbs, dwbs);
                onEntryNeedData = dwbs -> onnext4entry(currentSubject.get(), bbs, dwbs);
            }

            final List<DisposableWrapper<? extends ByteBuf>> dwbs = new ArrayList<>();
            bufout.setOutput(dwb -> dwbs.add(dwb));

            try {
                final int readed = syncunzip(zipin, bufout, readbuf);
                if (-1 == readed) {
                    // means end of entry
                    LOG.debug("syncunzip return -1, current entry completed.");
                    afterNextEntry.set(onEntryComplete.call(dwbs));
                }
            } catch (final IOException e) {
                if (e instanceof NoDataException) {
                    LOG.debug("syncunzip throw NoDataException, stop unzip and wait for input.");
                    onEntryNeedData.call(dwbs);
                    return entities(entities);
                } else {
                    return Observable.error(e);
                }
            }
        }
    }

    private static PublishSubject<ByteBufSlice> newentity4entry(
            final List<UnzipEntity> entities,
            final ZipEntry entry,
            final ByteBufSlice bbs,
            final List<DisposableWrapper<? extends ByteBuf>> dwbs) {
        final PublishSubject<ByteBufSlice> subject = PublishSubject.create();
        entities.add(new UnzipEntity() {
            @Override
            public ZipEntry entry() {
                return entry;
            }

            @Override
            public Observable<? extends ByteBufSlice> body() {
                return Observable.<ByteBufSlice>just(new ByteBufSlice() {
                    @Override
                    public String toString() {
                        return new StringBuilder()
                                .append("ByteBufSlice [unzip begin entry=").append(entry)
                                .append(", unzipped for ").append(bbs).append("]").toString();
                    }

                    @Override
                    public void step() {
                        bbs.step();
                    }

                    @Override
                    public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                        return dwbs;
                    }})
                    .concatWith(subject);
            }}
        );
        return subject;
    }

    private static ZipEntry getNextEntry(final ZipInputStreamX zipin, final Action1<Boolean> afterNextEntry)
            throws IOException {
        ZipEntry entry = null;
        try {
            // if return null means End Of Stream
            entry = zipin.getNextEntry();
            if (null != entry) {
                LOG.info("zipin.getNextEntry() return: {}, get new UnzipEntity", entry);
                doWithHasNext(afterNextEntry, true);
            } else {
                // TODO , no more entries
                LOG.debug("zipin.getNextEntry() return null, no more entries");
                doWithHasNext(afterNextEntry, false);
            }
        } catch (final IOException e) {
            if (e instanceof NoDataException) {
                LOG.debug("zipin.getNextEntry() throw NoDataException, stop unzip and wait for input");
                doWithHasNext(afterNextEntry, false);
            } else {
                LOG.debug("zipin.getNextEntry() throw {}", ExceptionUtils.exception2detail(e));
                throw e;
            }
        }
        return entry;
    }

    private static void doWithHasNext(final Action1<Boolean> action, final boolean hasNextEntry) {
        if (null != action) {
            action.call(hasNextEntry);
        }
    }

    private static Observable<? extends UnzipEntity> entities(final Collection<UnzipEntity> entities) {
        return entities.isEmpty() ? Observable.empty() : Observable.from(entities);
    }

    private static void onnext4entry(
            final PublishSubject<ByteBufSlice> subject,
            final ByteBufSlice bbs,
            final List<DisposableWrapper<? extends ByteBuf>> dwbs) {
        subject.onNext(new ByteBufSlice() {
            @Override
            public String toString() {
                return new StringBuilder().append("ByteBufSlice [unzip part for ").append(bbs).append("]").toString();
            }

            @Override
            public void step() {
                bbs.step();
            }

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return dwbs;
            }});
    }

    private static Action1<Boolean> complete4entry(
            final List<UnzipEntity> entities,
            final ZipEntry entry,
            final ByteBufSlice bbs,
            final List<DisposableWrapper<? extends ByteBuf>> dwbs) {
        return hasNextEntry -> {
            entities.add(new UnzipEntity() {
                @Override
                public ZipEntry entry() {
                    return entry;
                }

                @Override
                public Observable<? extends ByteBufSlice> body() {
                    return Observable.just(bbsof(!hasNextEntry, bbs, dwbs));
                }});
        };
    }

    private static Action1<Boolean> complete4entry(
            final PublishSubject<ByteBufSlice> subject,
            final ByteBufSlice bbs,
            final List<DisposableWrapper<? extends ByteBuf>> dwbs) {
        return hasNextEntry -> {
            subject.onNext(bbsof(!hasNextEntry, bbs, dwbs));
            subject.onCompleted();
        };
    }

    private static ByteBufSlice bbsof(
            final boolean callstep,
            final ByteBufSlice bbs,
            final List<DisposableWrapper<? extends ByteBuf>> dwbs) {
        return new ByteBufSlice() {
            @Override
            public String toString() {
                return new StringBuilder().append("ByteBufSlice [unzip part for ").append(bbs)
                        .append(", step enabled=").append(callstep).append("]").toString();
            }

            @Override
            public void step() {
                if (callstep) {
                    bbs.step();
                }
            }

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return dwbs;
            }};
    }

    private static int syncunzip(final ZipInputStreamX zipin,
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout,
            final byte[] readbuf) throws IOException {
        int readed = 0;
        try {
            do {
                readed = zipin.read(readbuf);
                if (readed > 0) {
                    bufout.write(readbuf, 0, readed);
                }
            } while (readed > 0);
        } finally {
            try {
                bufout.flush();
            } catch (final IOException e) {}
        }
        return readed;
    }

    public static Zipper zipEntities(
            final Func0<DisposableWrapper<? extends ByteBuf>> allocator,
            final Endable endable,
            final int bufsize,
            final Action1<DisposableWrapper<? extends ByteBuf>> onzipped) {
        return entities -> {
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout = new BufsOutputStream<>(allocator, dwb->dwb.unwrap());
            final ZipOutputStream zipout = new ZipOutputStream(bufout, CharsetUtil.UTF_8);
            zipout.setLevel(Deflater.BEST_COMPRESSION);

            final byte[] readbuf = new byte[bufsize];

            endable.doOnEnd(() -> {
                try {
                    zipout.close();
                } catch (final IOException e1) {
                }
            });

            return entities.concatMap(entity ->
                        // start zip: entry info
                        Observable.defer(()->zipentry(entity.entryName(), zipout, bufout)).concatWith(
                        // zip content
                        entity.body().map(dozip(zipout, bufout, readbuf, onzipped))).concatWith(
                         // close entry
                        Observable.defer(()->closeentry(zipout, bufout)) ))
                    // end of zip: finish all
                    .concatWith(Observable.defer(()->finishzip(zipout, bufout)));
        };
    }

    private static Observable<ByteBufSlice> zipentry(
            final String entryName,
            final ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout) {
        final Iterable<? extends DisposableWrapper<? extends ByteBuf>> zipped = MessageUtil.out2dwbs(bufout, out -> {
            try {
                zipout.putNextEntry(new ZipEntry(entryName));
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });
        return Observable.just(new ByteBufSlice() {
            @Override
            public String toString() {
                return new StringBuilder().append("ByteBufSlice [begin zip entry=").append(entryName).append("]").toString();
            }

            @Override
            public void step() {}

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return zipped;
            }});
    }

    private static Func1<ByteBufSlice, ByteBufSlice> dozip(
            final ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout,
            final byte[] readbuf,
            final Action1<DisposableWrapper<? extends ByteBuf>> onzipped) {
        return bbs -> {
            final Iterable<? extends DisposableWrapper<? extends ByteBuf>> zipped =
                    MessageUtil.out2dwbs(bufout, out -> append4zip(zipout, bbs.element(), readbuf, onzipped));

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
                public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                    return zipped;
                }
            };
        };
    }

    private static Observable<ByteBufSlice> closeentry(
            final ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout) {
        final Iterable<? extends DisposableWrapper<? extends ByteBuf>> zipped = MessageUtil.out2dwbs(bufout, out -> {
            try {
                zipout.closeEntry();
                zipout.flush();
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });
        return Observable.just(new ByteBufSlice() {
            @Override
            public String toString() {
                return "ByteBufSlice [closeentry]";
            }
            @Override
            public void step() {}

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return zipped;
            }});
    }

    private static Observable<ByteBufSlice> finishzip(
            final ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout) {
        final Iterable<? extends DisposableWrapper<? extends ByteBuf>> zipped = MessageUtil.out2dwbs(bufout, out -> {
            try {
                zipout.finish();
                zipout.close();
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });
        return Observable.just(new ByteBufSlice() {
            @Override
            public String toString() {
                return "ByteBufSlice [finishzip]";
            }
            @Override
            public void step() {}

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return zipped;
            }});
    }

    private static void append4zip(final ZipOutputStream zipout,
            final Iterable<? extends DisposableWrapper<? extends ByteBuf>> dwbs,
            final byte[] readbuf,
            final Action1<DisposableWrapper<? extends ByteBuf>> onzipped) {
        try (final BufsInputStream<DisposableWrapper<? extends ByteBuf>> is =
                    new BufsInputStream<>(dwb->dwb.unwrap(), onzipped)) {
            is.appendIterable(dwbs);
            is.markEOS();
            int readed;
            while ((readed = is.read(readbuf)) > 0) {
                zipout.write(readbuf, 0, readed);
            }
//            zipout.flush();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Zipper zipEntitiesWithPassword(
            final Func0<DisposableWrapper<? extends ByteBuf>> allocator,
            final Endable endable,
            final int bufsize,
            final Action1<DisposableWrapper<? extends ByteBuf>> onzipped,
            final String password) {
        return entities -> {
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout = new BufsOutputStream<>(allocator, dwb->dwb.unwrap());
            final net.lingala.zip4j.io.ZipOutputStream zipout = new net.lingala.zip4j.io.ZipOutputStream(bufout);

            final byte[] readbuf = new byte[bufsize];

            endable.doOnEnd(() -> {
                try {
                    zipout.close();
                } catch (final IOException e1) {
                }
            });

            return entities.concatMap(entity ->
                        // start zip: entry info
                        Observable.defer(()->{
                            final ZipParameters zipParameters = new ZipParameters();
                            zipParameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
                            zipParameters.setCompressionLevel(9);
                            zipParameters.setEncryptFiles(true);
                            zipParameters.setEncryptionMethod(Zip4jConstants.ENC_METHOD_STANDARD);
                            zipParameters.setPassword(password);
                            zipParameters.setSourceExternalStream(true);
                            zipParameters.setFileNameInZip(entity.entryName());

                            return zipentry(zipParameters, zipout, bufout);
                        }).concatWith(
                        // zip content
                        entity.body().map(dozip(zipout, bufout, readbuf, onzipped))).concatWith(
                         // close entry
                        Observable.defer(()->closeentry(zipout, bufout)) ))
                    // end of zip: finish all
                    .concatWith(Observable.defer(()->finishzip(zipout, bufout)));
        };
    }


    private static Observable<ByteBufSlice> zipentry(
            final ZipParameters zipParameters,
            final net.lingala.zip4j.io.ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout) {
        final String entryName = zipParameters.getFileNameInZip();
        final Iterable<? extends DisposableWrapper<? extends ByteBuf>> zipped = MessageUtil.out2dwbs(bufout, out -> {
            try {
                zipout.putNextEntry(null, zipParameters);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        });
        return Observable.just(new ByteBufSlice() {
            @Override
            public String toString() {
                return new StringBuilder().append("ByteBufSlice [begin zip entry=").append(entryName).append("]").toString();
            }

            @Override
            public void step() {}

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return zipped;
            }});
    }

    private static Func1<ByteBufSlice, ByteBufSlice> dozip(
            final net.lingala.zip4j.io.ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout,
            final byte[] readbuf,
            final Action1<DisposableWrapper<? extends ByteBuf>> onzipped) {
        return bbs -> {
            final Iterable<? extends DisposableWrapper<? extends ByteBuf>> zipped =
                    MessageUtil.out2dwbs(bufout, out -> append4zip(zipout, bbs.element(), readbuf, onzipped));

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
                public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                    return zipped;
                }
            };
        };
    }

    private static Observable<ByteBufSlice> closeentry(
            final net.lingala.zip4j.io.ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout) {
        final Iterable<? extends DisposableWrapper<? extends ByteBuf>> zipped = MessageUtil.out2dwbs(bufout, out -> {
            try {
                zipout.closeEntry();
                zipout.flush();
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        });
        return Observable.just(new ByteBufSlice() {
            @Override
            public String toString() {
                return "ByteBufSlice [closeentry]";
            }
            @Override
            public void step() {}

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return zipped;
            }});
    }

    private static Observable<ByteBufSlice> finishzip(
            final net.lingala.zip4j.io.ZipOutputStream zipout,
            final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout) {
        final Iterable<? extends DisposableWrapper<? extends ByteBuf>> zipped = MessageUtil.out2dwbs(bufout, out -> {
            try {
                zipout.finish();
                zipout.close();
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        });
        return Observable.just(new ByteBufSlice() {
            @Override
            public String toString() {
                return "ByteBufSlice [finishzip]";
            }
            @Override
            public void step() {}

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return zipped;
            }});
    }

    private static void append4zip(final net.lingala.zip4j.io.ZipOutputStream zipout,
            final Iterable<? extends DisposableWrapper<? extends ByteBuf>> dwbs,
            final byte[] readbuf,
            final Action1<DisposableWrapper<? extends ByteBuf>> onzipped) {
        try (final BufsInputStream<DisposableWrapper<? extends ByteBuf>> is =
                    new BufsInputStream<>(dwb->dwb.unwrap(), onzipped)) {
            is.appendIterable(dwbs);
            is.markEOS();
            int readed;
            while ((readed = is.read(readbuf)) > 0) {
                zipout.write(readbuf, 0, readed);
            }
//            zipout.flush();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
