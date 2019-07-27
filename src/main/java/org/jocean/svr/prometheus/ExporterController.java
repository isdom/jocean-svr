package org.jocean.svr.prometheus;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.Enumeration;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

import org.jocean.http.WriteCtrl;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.Stepable;
import org.jocean.svr.WithStepable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import rx.Observable;
import rx.functions.Action2;

@Controller
@Scope("singleton")
public class ExporterController {

    private static final Logger LOG = LoggerFactory.getLogger(ExporterController.class);

    @Path("/metrics")
    @GET
    public WithStepable<Stepable<Enumeration<Collector.MetricFamilySamples>>> getMetrics(final WriteCtrl writeCtrl) {

        writeCtrl.sended().subscribe(obj -> DisposableWrapperUtil.dispose(obj));

        return new WithStepable<Stepable<Enumeration<Collector.MetricFamilySamples>>>() {
            @Override
            public String contentType() {
                return TextFormat.CONTENT_TYPE_004;
            }

            @Override
            public Observable<Stepable<Enumeration<Collector.MetricFamilySamples>>> stepables() {
                return Observable.just(new Stepable<Enumeration<Collector.MetricFamilySamples>>() {
                            @Override
                            public void step() {}

                            @Override
                            public Enumeration<Collector.MetricFamilySamples> element() {
                                return  _registry.filteredMetricFamilySamples(/*parseQuery(query)*/Collections.emptySet());
                            }
                        });
            }

            @Override
            public Action2<Stepable<Enumeration<Collector.MetricFamilySamples>>, OutputStream> output() {
                return (stepable, out) -> {
                    final long start = System.currentTimeMillis();
                    try(final OutputStreamWriter osw = new OutputStreamWriter(out)) {
                        TextFormat.write004(osw, stepable.element());
                        osw.flush();
                    } catch (final IOException e) {
//                        // TODO Auto-generated catch block
//                        e.printStackTrace();
                    } finally {
                        LOG.info("restin /metrics's TextFormat.write004 cost: {}", System.currentTimeMillis() - start);
                    }
                };
            }
        };
    }

    CollectorRegistry _registry = CollectorRegistry.defaultRegistry;
}
