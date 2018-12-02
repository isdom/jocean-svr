package org.jocean.svr.hystrix;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

import com.netflix.hystrix.metric.consumer.HystrixDashboardStream;
import com.netflix.hystrix.serial.SerialHystrixDashboardData;

import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.functions.Action2;

@Controller
@Scope("singleton")
public class HystrixMetricsStreamController {

    private static final Logger LOG = LoggerFactory.getLogger(HystrixMetricsStreamController.class);

    @Path("/hystrix.stream")
    @GET
    public WithStepable<Stepable<List<String>>> getStream(final WriteCtrl writeCtrl) {

        writeCtrl.sended().subscribe(obj -> DisposableWrapperUtil.dispose(obj));

        return new WithStepable<Stepable<List<String>>>() {
            @Override
            public String contentType() {
                return "text/event-stream;charset=UTF-8";
            }

            @Override
            public Observable<Stepable<List<String>>> stepables() {
                final AtomicBoolean stepCalled = new AtomicBoolean(true);
                return HystrixDashboardStream.getInstance().observe()
                        .filter(dashboardData -> stepCalled.getAndSet(false))
                        .map(dashboardData -> SerialHystrixDashboardData.toMultipleJsonStrings(dashboardData))
                        .map(strs -> new Stepable<List<String>>() {
                            @Override
                            public void step() {
                                stepCalled.set(true);
                                LOG.debug("getStream DashboardData's step(...)");
                            }

                            @Override
                            public List<String> element() {
                                final List<String> body = new ArrayList<>();
                                for (final String s : strs) {
                                    body.add(new StringBuilder().append("data: ").append(s).append("\n\n").toString());
                                }
                                return body;
                            }
                        });
            }

            @Override
            public Action2<Stepable<List<String>>, OutputStream> output() {
                return (stepable, out) -> {
                    for ( final String s : stepable.element()) {
                        try {
                            out.write(s.getBytes(CharsetUtil.UTF_8));
                        } catch (final IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                };
            }
        };
    }
}
