package org.jocean.svr.prometheus;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageUtil;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.j2se.prometheus.TextFormatUtil;
import org.jocean.netty.util.BufsOutputStream;
import org.jocean.svr.TradeContext;
import org.jocean.svr.WithSlice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;

import io.netty.buffer.ByteBuf;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.CollectorRegistry;
import rx.Observable;
import rx.functions.Func0;

@Controller
@Scope("singleton")
public class ExporterController {

    private static final Logger LOG = LoggerFactory.getLogger(ExporterController.class);

    @Path("/metrics")
    @GET
    public WithSlice getMetrics(final TradeContext tctx) {

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
                "application",  System.getProperty("app.name"),
                "hostname",     System.getenv("HOSTNAME"),
                "app.build",    System.getProperty("service.buildno")
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


        final long start = System.currentTimeMillis();
        final String startThread = Thread.currentThread().getName();

        return new WithSlice() {
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
                    return new ByteBufSlice() {
                        @Override
                        public void step() {}

                        @Override
                        public Iterable<? extends DisposableWrapper<? extends ByteBuf>> element() {
                            return dwblist;
                        }};
                });
            }};
    }

    CollectorRegistry _registry = CollectorRegistry.defaultRegistry;
}
