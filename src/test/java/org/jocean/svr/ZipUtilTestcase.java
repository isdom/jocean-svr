package org.jocean.svr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageUtil;
import org.jocean.http.util.Nettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Terminable;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.svr.ZipUtil.TozipEntity;
import org.jocean.svr.ZipUtil.UnzipEntity;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.schedulers.Schedulers;

public class ZipUtilTestcase {

    private static final Logger LOG
        = LoggerFactory.getLogger(ZipUtilTestcase.class);

    private static Executor exec = Executors.newSingleThreadExecutor();

    private TozipEntity entityOf(final String name, final byte[] bytes) {
        return new TozipEntity() {
            @Override
            public String entryName() {
                return name;
            }

            @Override
            public Observable<? extends ByteBufSlice> body() {
                return Observable.just(new ByteBufSlice() {
                    @Override
                    public void step() {}

                    @Override
                    public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                        return Observable.just(DisposableWrapperUtil.wrap(Unpooled.wrappedBuffer(bytes), (Action1<ByteBuf>)null))
                                .toList().toBlocking().single();
                    }});
            }};
    }

    private Transformer<TozipEntity, ByteBufSlice> zipBy(final Terminable terminable) {
        return ZipUtil.zipEntities(MessageUtil.pooledAllocator(terminable, 8192), terminable, 512, dwb->dwb.dispose());
    }

    private Transformer<ByteBufSlice, UnzipEntity> unzipBy(final Terminable terminable) {
        return ZipUtil.unzipToEntities(MessageUtil.pooledAllocator(terminable, 8192), terminable, 512, dwb->dwb.dispose());
    }

    @Test
    public final void testUnzipEntities() throws IOException, InterruptedException {
        final Terminable terminable = new Terminable() {
            @Override
            public Action1<Action0> onTerminate() {
                return null;
            }

            @Override
            public Action0 doOnTerminate(final Action0 onTerminate) {
                return null;
            }};

        final byte[] c1 = new byte[256];
        c1[0] = 21;

        final byte[] c2 = new byte[256];
        c2[0] = 22;

        final List<ZipEntry> entries = new ArrayList<>();
        final List<byte[]> bodys = new ArrayList<>();

        final Object watcher = new Object();

        Observable.just(entityOf("1", c1), entityOf("2", c2))
            .doOnNext(entity -> {
                LOG.debug("=========== to zip entity: {}", entity.entryName());
                final List<? extends DisposableWrapper<? extends ByteBuf>> dwbs = entity.body().compose(MessageUtil.AUTOSTEP2DWB).toList().toBlocking().single();
                LOG.debug("------------ zipped begin");
                for (final DisposableWrapper<? extends ByteBuf> dwb : dwbs) {
                    LOG.debug("to zip content:\r\n{}", ByteBufUtil.prettyHexDump(dwb.unwrap()));
                }
                LOG.debug("------------ to zip content end");
            })
            .compose(zipBy(terminable))
            .doOnNext( bbs -> {
                LOG.debug("=========== zipped slice: {}", bbs);
                final List<? extends DisposableWrapper<? extends ByteBuf>> dwbs = Observable.from(bbs.element()).toList().toBlocking().single();
                LOG.debug("------------ zipped begin");
                for (final DisposableWrapper<? extends ByteBuf> dwb : dwbs) {
                    LOG.debug("zipped:\r\n{}", ByteBufUtil.prettyHexDump(dwb.unwrap()));
                }
                LOG.debug("------------ zipped end");
            })
            .compose(unzipBy(terminable))
            .subscribeOn(Schedulers.from(exec))
            .subscribe(entity -> {
                LOG.debug("entry: {}", entity.entry());
                entries.add(entity.entry());
                entity.body().compose(MessageUtil.AUTOSTEP2DWB).toList().subscribe(dwbs -> {
                    final ByteBuf c = Nettys.dwbs2buf(dwbs);
                    try {
                        LOG.debug("unzipped content\r\n {}", ByteBufUtil.prettyHexDump(c));
                        bodys.add(Nettys.dumpByteBufAsBytes(c));
                    } catch (final IOException e) {
                    } finally {
                        c.release();
                    }
                });
            }, e -> {
                LOG.info("exception when test, detail: {}", ExceptionUtils.exception2detail(e));
            }, () -> {
                synchronized(watcher) {
                    watcher.notify();
                }
            });

        synchronized(watcher) {
            watcher.wait();
        }

        assertEquals(2, entries.size());
        assertEquals(2, bodys.size());

        assertEquals("1", entries.get(0).getName());
        assertTrue(Arrays.equals(c1, bodys.get(0)));

        assertEquals("2", entries.get(1).getName());
        assertTrue(Arrays.equals(c2, bodys.get(1)));
    }

    @Test
    public final void testUnzipEntitiesWithinSlice() throws IOException, InterruptedException {
        final Terminable terminable = new Terminable() {
            @Override
            public Action1<Action0> onTerminate() {
                return null;
            }

            @Override
            public Action0 doOnTerminate(final Action0 onTerminate) {
                return null;
            }};

        final byte[] c1 = new byte[256];
        c1[0] = 21;

        final byte[] c2 = new byte[256];
        c2[0] = 22;

        final Func0<DisposableWrapper<ByteBuf>>allocator = MessageUtil.pooledAllocator(terminable, 8192);

        final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout = new BufsOutputStream<>(allocator, dwb->dwb.unwrap());
        final ZipOutputStream zipout = new ZipOutputStream(bufout, CharsetUtil.UTF_8);
        zipout.setLevel(Deflater.BEST_COMPRESSION);

        final Iterable<DisposableWrapper<ByteBuf>> zipped = MessageUtil.out2dwbs(bufout, (out)-> {
            try {
                zipout.putNextEntry(new ZipEntry("1"));
                zipout.write(c1);
                zipout.closeEntry();
                zipout.putNextEntry(new ZipEntry("2"));
                zipout.write(c2);
                zipout.closeEntry();
                zipout.finish();
                zipout.close();
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        });

        final List<ZipEntry> entries = new ArrayList<>();
        final List<byte[]> bodys = new ArrayList<>();

        final Object watcher = new Object();

        Observable.just(new ByteBufSlice() {
            @Override
            public void step() {}

            @Override
            public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                return zipped;
            }})
            .doOnNext( bbs -> {
                LOG.debug("=========== zipped slice: {}", bbs);
                final List<? extends DisposableWrapper<? extends ByteBuf>> dwbs = Observable.from(bbs.element())
                        .toList().toBlocking().single();
                LOG.debug("------------ zipped begin");
                for (final DisposableWrapper<? extends ByteBuf> dwb : dwbs) {
                    LOG.debug("zipped:\r\n{}", ByteBufUtil.prettyHexDump(dwb.unwrap()));
                }
                LOG.debug("------------ zipped end");
            })
            .compose(unzipBy(terminable))
            .subscribeOn(Schedulers.from(exec))
            .subscribe(entity -> {
                LOG.debug("entry: {}", entity.entry());
                entries.add(entity.entry());
                entity.body().compose(MessageUtil.AUTOSTEP2DWB).toList().subscribe(dwbs -> {
                    final ByteBuf c = Nettys.dwbs2buf(dwbs);
                    try {
                        LOG.debug("unzipped content\r\n {}", ByteBufUtil.prettyHexDump(c));
                        bodys.add(Nettys.dumpByteBufAsBytes(c));
                    } catch (final IOException e) {
                    } finally {
                        c.release();
                    }
                });
            }, e -> {
                LOG.info("exception when test, detail: {}", ExceptionUtils.exception2detail(e));
            }, () -> {
                synchronized(watcher) {
                    watcher.notify();
                }
            });

        synchronized(watcher) {
            watcher.wait();
        }

        assertEquals(2, entries.size());
        assertEquals(2, bodys.size());

        assertEquals("1", entries.get(0).getName());
        assertTrue(Arrays.equals(c1, bodys.get(0)));

        assertEquals("2", entries.get(1).getName());
        assertTrue(Arrays.equals(c2, bodys.get(1)));
    }

    @Test
    public final void testSyncZip() throws IOException {
        final Func0<DisposableWrapper<ByteBuf>>allocator = MessageUtil.pooledAllocator(null, 8192);

        final BufsOutputStream<DisposableWrapper<ByteBuf>> bufout = new BufsOutputStream<>(allocator, dwb->dwb.unwrap());
        final ZipOutputStream zipout = new ZipOutputStream(bufout, CharsetUtil.UTF_8);
        zipout.setLevel(Deflater.BEST_COMPRESSION);

        final List<DisposableWrapper<? extends ByteBuf>> dwbs = new ArrayList<>();
        bufout.setOutput(dwb -> dwbs.add(dwb));

        zipout.putNextEntry(new ZipEntry("1"));

        final byte[] c1 = new byte[256];
        c1[0] = 21;
        zipout.write(c1);
        zipout.closeEntry();

        zipout.putNextEntry(new ZipEntry("2"));
        final byte[] c2 = new byte[256];
        c2[0] = 22;
        zipout.write(c2);
        zipout.closeEntry();
        zipout.finish();
        zipout.close();
        bufout.flush();

        LOG.debug("all zipped:\r\n{}", ByteBufUtil.prettyHexDump(Nettys.dwbs2buf(dwbs)));

        final byte[] zipped = Nettys.dumpByteBufAsBytes(Nettys.dwbs2buf(dwbs));
        final ZipInputStream zipin = new ZipInputStream(new ByteArrayInputStream(zipped));
        final byte[] readbuf = new byte[512];
        int readed;

        final ZipEntry z1 = zipin.getNextEntry();
        LOG.debug("z1 entry: {}", z1);
        readed = zipin.read(readbuf);
        LOG.debug("z1 content size:{} \r\n {}", readed, ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(readbuf, 0, readed)));

        final ZipEntry z2 = zipin.getNextEntry();
        LOG.debug("z2 entry: {}", z2);
        readed = zipin.read(readbuf);
        LOG.debug("z2 content size:{} \r\n {}", readed, ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(readbuf, 0, readed)));
    }
}
