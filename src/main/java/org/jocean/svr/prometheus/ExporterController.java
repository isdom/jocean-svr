package org.jocean.svr.prometheus;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageUtil;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.j2se.prometheus.TextFormatUtil;
import org.jocean.netty.util.BufsInputStream;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.svr.ResponseBean;
import org.jocean.svr.ResponseUtil;
import org.jocean.svr.TradeContext;
import org.jocean.svr.WithBody;
import org.jocean.svr.WithHeader;
import org.jocean.svr.WithSlice;
import org.jocean.svr.WithStatus;
import org.jocean.svr.mbean.RestinIndicatorMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.CollectorRegistry;
import rx.Observable;
import rx.functions.Func0;

@Controller
@Scope("singleton")
public class ExporterController {
    static final int _NONE = 0;
    static final int _GZIP = 1;
    static final int _DEFLATE = 2;

    private static final Logger LOG = LoggerFactory.getLogger(ExporterController.class);

    @Path("/metrics")
    @GET
    public ResponseBean getMetrics(final TradeContext tctx,
            @HeaderParam("accept-encoding") final String acceptEncoding) {

        tctx.writeCtrl().sended().subscribe(obj -> DisposableWrapperUtil.dispose(obj));

        final Enumeration<Collector.MetricFamilySamples> mfs =
                _registry.filteredMetricFamilySamples(/*parseQuery(query)*/Collections.emptySet());
        final List<Collector.MetricFamilySamples> mfarray[] =
                (List<MetricFamilySamples>[]) Array.newInstance(List.class, tctx.scheduler().workerCount());
        for (int idx = 0; idx < mfarray.length; idx++) {
            mfarray[idx] = new ArrayList<>();
        }
        int idx = 0;
        while(mfs.hasMoreElements()) {
            mfarray[idx++].add(mfs.nextElement());
            idx %= mfarray.length;
        }

        final String[] labels = new String[]{
                "app_build",    System.getProperty("service.buildno"),
                "application",  System.getProperty("app.name"),
                "hostname",     System.getenv("HOSTNAME")
        };

        final Func0<DisposableWrapper<? extends ByteBuf>> allocator = tctx.allocatorBuilder().build(8192);
        final List<Observable<Iterable<DisposableWrapper<? extends ByteBuf>>>> tobbs = new ArrayList<>();
        for (idx = 0; idx < mfarray.length; idx++) {
            final List<MetricFamilySamples> mflist = mfarray[idx];
            tobbs.add(Observable.defer(() -> {
                Iterable<DisposableWrapper<? extends ByteBuf>> dwbs = Collections.emptyList();
                try (final BufsOutputStream<DisposableWrapper<? extends ByteBuf>> bufout =
                        new BufsOutputStream<>(allocator, dwb -> dwb.unwrap())) {
                    dwbs = MessageUtil.out2dwbs(bufout, out -> {
                        try (final OutputStreamWriter osw = new OutputStreamWriter(bufout)) {
                            for (final MetricFamilySamples mf : mflist) {
                                try {
                                    TextFormatUtil.write004(osw, mf, labels);
                                } catch (final IOException e) {
                                }
                            }
                            osw.flush();
                        } catch (final IOException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    });
                } catch (final IOException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                }
                return Observable.just(dwbs);
            }).subscribeOn(tctx.scheduler().scheduler()));
        }

        final int targetContentEncoding = determineWrapper(acceptEncoding);
        final long start = System.currentTimeMillis();
        final String startThread = Thread.currentThread().getName();

        final WithSlice body = new WithSlice() {
            @Override
            public String contentType() {
                return TextFormatUtil.CONTENT_TYPE_004;
            }

            @Override
            public Observable<? extends ByteBufSlice> slices() {
                return Observable.<ByteBufSlice>zip(tobbs, dwbsarray -> {
                    final List<DisposableWrapper<? extends ByteBuf>> dwblist = new ArrayList<>();
                    for (final Object dwbs : dwbsarray) {
                        final Iterator<DisposableWrapper<? extends ByteBuf>> iter = ((Iterable<DisposableWrapper<? extends ByteBuf>>)dwbs).iterator();
                        while (iter.hasNext()) {
                            dwblist.add(iter.next());
                        }
                    }
                    LOG.info("restin:{} /metrics's concurrent({}) TextFormat.write004 cost: {}, begin:{}/end:{}",
                            tctx.restin().getPort(),
                            tobbs.size(),
                            System.currentTimeMillis() - start,
                            startThread,
                            Thread.currentThread().getName());

                    final Iterable<? extends DisposableWrapper<? extends ByteBuf>> element =
                            buildElement(targetContentEncoding, dwblist, allocator, tctx.restin());

                    return new ByteBufSlice() {
                        @Override
                        public void step() {}

                        @Override
                        public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                            return element;
                        }};
                });
            }};

        return new ResponseBean() {
            @Override
            public WithStatus withStatus() {
                return null;
            }
            @Override
            public WithHeader withHeader() {
                return buildContentEncoding(targetContentEncoding);
            }
            @Override
            public WithBody withBody() {
                return body;
            }};
    }

    protected WithHeader buildContentEncoding(final int targetContentEncoding) {
        if (targetContentEncoding == _NONE) {
            return null;
        }
        else {
            return ResponseUtil.header().setHeader(HttpHeaderNames.CONTENT_ENCODING.toString(),
                    _GZIP == targetContentEncoding ? "gzip" : "deflate");
        }
    }

    private static final byte[] gzipHeader = {0x1f, (byte) 0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, 0};

    protected Iterable<? extends DisposableWrapper<? extends ByteBuf>> buildElement(
            final int targetContentEncoding,
            final List<DisposableWrapper<? extends ByteBuf>> input,
            final Func0<DisposableWrapper<? extends ByteBuf>> allocator,
            final RestinIndicatorMXBean restin) {
        if (targetContentEncoding == _NONE) {
            return input;
        }
        else {
            final long start = System.currentTimeMillis();
            final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin =
                    new BufsInputStream<>(dwb->dwb.unwrap(), dwb -> dwb.dispose());

            bufin.appendIterable(input);
            bufin.markEOS();

            final Iterable<? extends DisposableWrapper<? extends ByteBuf>> dwbs = MessageUtil.out2dwbs(
                    new BufsOutputStream<>(allocator, dwb -> dwb.unwrap()),
                    out -> deflate(bufin, out, targetContentEncoding) );
            LOG.info("restin:{} /metrics's compress {} cost: {}",
                    restin.getPort(),
                    targetContentEncoding == _GZIP ? "gzip" : "deflate",
                    System.currentTimeMillis() - start);

            return dwbs;
        }
    }

    private void deflate(final BufsInputStream<DisposableWrapper<? extends ByteBuf>> bufin,
            final OutputStream out,
            final int targetContentEncoding) {
        final byte[] buf = new byte[512];
        final Deflater deflater = new Deflater(6, true);
        final CRC32 crc = new CRC32();

        try {
            if (targetContentEncoding == _GZIP) {
                out.write(gzipHeader);
            }
            int readed = -1;
            do {
                readed = bufin.read(buf);
                if (readed > 0) {
                    if (targetContentEncoding == _GZIP) {
                        crc.update(buf, 0, readed);
                    }
                    deflater.setInput(buf, 0, readed);

                    deflatePart(deflater, buf, out);

//                            if (deflater.needsInput()) {
//                                // Consumed everything
//                            }
                }
            } while(readed > 0);

            deflater.finish();

            while (!deflater.finished()) {
                deflatePart(deflater, buf, out);
            }

            if (targetContentEncoding == _GZIP) {
                final int crcValue = (int) crc.getValue();
                final int uncBytes = deflater.getTotalIn();
                out.write(crcValue);
                out.write(crcValue >>> 8);
                out.write(crcValue >>> 16);
                out.write(crcValue >>> 24);
                out.write(uncBytes);
                out.write(uncBytes >>> 8);
                out.write(uncBytes >>> 16);
                out.write(uncBytes >>> 24);
            }

            deflater.end();
            out.flush();
        } catch (final IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void deflatePart(final Deflater deflater, final byte[] buf, final OutputStream out) throws IOException {
        int compressed = -1;
        do {
            compressed = deflater.deflate(buf);
            if (compressed > 0) {
                out.write(buf, 0, compressed);
            }
        } while (compressed > 0);
    }

    protected int determineWrapper(final String acceptEncoding) {
        if (null == acceptEncoding) {
            return _NONE;
        }
        float starQ = -1.0f;
        float gzipQ = -1.0f;
        float deflateQ = -1.0f;
        for (final String encoding : acceptEncoding.split(",")) {
            float q = 1.0f;
            final int equalsPos = encoding.indexOf('=');
            if (equalsPos != -1) {
                try {
                    q = Float.parseFloat(encoding.substring(equalsPos + 1));
                } catch (final NumberFormatException e) {
                    // Ignore encoding
                    q = 0.0f;
                }
            }
            if (encoding.contains("*")) {
                starQ = q;
            } else if (encoding.contains("gzip") && q > gzipQ) {
                gzipQ = q;
            } else if (encoding.contains("deflate") && q > deflateQ) {
                deflateQ = q;
            }
        }
        if (gzipQ > 0.0f || deflateQ > 0.0f) {
            if (gzipQ >= deflateQ) {
                return _GZIP;
            } else {
                return _DEFLATE;
            }
        }
        if (starQ > 0.0f) {
            if (gzipQ == -1.0f) {
                return _GZIP;
            }
            if (deflateQ == -1.0f) {
                return _DEFLATE;
            }
        }
        return _NONE;
    }

    CollectorRegistry _registry = CollectorRegistry.defaultRegistry;
}
