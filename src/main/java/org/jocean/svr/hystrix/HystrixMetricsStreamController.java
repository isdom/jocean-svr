package org.jocean.svr.hystrix;

import java.io.IOException;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

import org.jocean.http.ByteBufSlice;
import org.jocean.http.MessageBody;
import org.jocean.idiom.Stepable;
import org.jocean.svr.AllocatorBuilder;
import org.jocean.svr.ByteBufSliceUtil;
import org.jocean.svr.WithBody;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;

import com.netflix.hystrix.metric.consumer.HystrixDashboardStream;
import com.netflix.hystrix.serial.SerialHystrixDashboardData;

import io.netty.util.CharsetUtil;
import rx.Observable;

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
    public WithBody getStream(final AllocatorBuilder ab) {
        return new WithBody() {
            @Override
            public Observable<? extends MessageBody> body() {
                return Observable.just(new MessageBody() {
                    @Override
                    public String contentType() {
                        return "text/event-stream;charset=UTF-8";
                    }

                    @Override
                    public int contentLength() {
                        return -1;
                    }

                    @Override
                    public Observable<? extends ByteBufSlice> content() {
                        return HystrixDashboardStream.getInstance().observe()
                                .concatMap(dashboardData -> Observable
                                        .from(SerialHystrixDashboardData.toMultipleJsonStrings(dashboardData)))
                                .map(str -> (Stepable<String>) new Stepable<String>() {
                                    @Override
                                    public void step() {
                                    }

                                    @Override
                                    public String element() {
                                        return new StringBuilder().append("data: ").append(str).append("\n\n")
                                                .toString();
                                    }
                                }).compose(ByteBufSliceUtil.stepable2bbs(ab.build(1024), (ss, out) -> {
                                    try {
                                        out.write(ss.element().getBytes(CharsetUtil.UTF_8));
                                    } catch (final IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                }));
                    }
                });
            }};
    }
}
