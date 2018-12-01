package org.jocean.svr.hystrix;

import java.io.IOException;
import java.io.OutputStream;

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

import com.netflix.hystrix.HystrixCollapserMetrics;
import com.netflix.hystrix.HystrixCommandMetrics;
import com.netflix.hystrix.HystrixThreadPoolMetrics;
import com.netflix.hystrix.metric.consumer.HystrixDashboardStream.DashboardData;
import com.netflix.hystrix.serial.SerialHystrixDashboardData;

import io.netty.util.CharsetUtil;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action2;

@Controller
@Scope("singleton")
public class HystrixMetricsStreamController {

    private static final Logger LOG = LoggerFactory.getLogger(HystrixMetricsStreamController.class);
    //  TODO, re-impl
//    static class StreamResponse implements WithBody {
//        @HeaderParam(HttpHeaders.CACHE_CONTROL)
//        private final String cacheControl = "no-cache, no-store, max-age=0, must-revalidate";
//
//        @HeaderParam("Pragma")
//        private final String Pragma = "no-cache";
//    }

    @Path("/hystrix.stream")
    @GET
    public WithStepable<Stepable<String>> getStream(final WriteCtrl writeCtrl) {

        writeCtrl.sended().subscribe(obj -> DisposableWrapperUtil.dispose(obj));

        return new WithStepable<Stepable<String>>() {
            @Override
            public String contentType() {
                return "text/event-stream;charset=UTF-8";
            }

            @Override
            public Observable<Stepable<String>> stepables() {
                return Observable.unsafeCreate(new OnSubscribe<Stepable<String>>() {
                    @Override
                    public void call(final Subscriber<? super Stepable<String>> subscriber) {
                        pushStepable(subscriber);
                    }});

//                return HystrixDashboardStream.getInstance().observe()
//                        .concatMap(dashboardData -> Observable
//                                .from(SerialHystrixDashboardData.toMultipleJsonStrings(dashboardData)))
//                        .map(str -> new Stepable<String>() {
//                            @Override
//                            public void step() {
//                                LOG.debug("getStream DashboardData's step(...)");
//                            }
//
//                            @Override
//                            public String element() {
//                                return new StringBuilder().append("data: ").append(str).append("\n\n").toString();
//                            }
//                        });
            }

            @Override
            public Action2<Stepable<String>, OutputStream> output() {
                return (ss, out) -> {
                    try {
                        out.write(ss.element().getBytes(CharsetUtil.UTF_8));
                    } catch (final IOException e) {
                        throw new RuntimeException(e);
                    }
                };
            }
        };
    }

    private void pushStepable(final Subscriber<? super Stepable<String>> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            subscriber.onNext(new Stepable<String>() {
                @Override
                public void step() {
                    pushStepable(subscriber);
                }

                @Override
                public String element() {
                    return new StringBuilder().append("data: ")
                        .append(SerialHystrixDashboardData.toMultipleJsonStrings(new DashboardData(
                            HystrixCommandMetrics.getInstances(),
                            HystrixThreadPoolMetrics.getInstances(),
                            HystrixCollapserMetrics.getInstances())))
                        .append("\n\n").toString();
                }});
        }
    }
}
