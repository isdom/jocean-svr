package org.jocean.svr.hystrix;

import java.io.IOException;
import java.io.OutputStream;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

import org.jocean.idiom.Stepable;
import org.jocean.svr.WithStepable;
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
    public WithStepable<Stepable<String>> getStream() {
        return new WithStepable<Stepable<String>>() {
            @Override
            public String contentType() {
                return "text/event-stream;charset=UTF-8";
            }

            @Override
            public Observable<Stepable<String>> content() {
                return HystrixDashboardStream.getInstance().observe()
                        .concatMap(dashboardData -> Observable
                                .from(SerialHystrixDashboardData.toMultipleJsonStrings(dashboardData)))
                        .map(str -> new Stepable<String>() {
                            @Override
                            public void step() {}

                            @Override
                            public String element() {
                                return new StringBuilder().append("data: ").append(str).append("\n\n").toString();
                            }
                        });
            }

            @Override
            public Action2<Stepable<String>, OutputStream> out() {
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
}
