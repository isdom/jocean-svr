package org.jocean.svr.interceptor;

import javax.inject.Inject;

import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxSubscribers;
import org.jocean.j2se.stats.ApiStats;
import org.jocean.svr.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.QueryStringDecoder;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;

public class StatRest implements MethodInterceptor {
    @SuppressWarnings("unused")
    private static final Logger LOG
        = LoggerFactory.getLogger(StatRest.class);

    private static String getRawPath(final String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            //  http://wangsz.xicp.net:10900/call/zmccCallEndNotify
            final int schemeIdx = path.indexOf("://");
            final String pathWithoutScheme = path.substring(schemeIdx + 3);
            final int rawPathIdx = pathWithoutScheme.indexOf('/');
            return (rawPathIdx > 0) ? pathWithoutScheme.substring(rawPathIdx) : "/";
        } else {
            return path;
        }
    }
    
    @Override
    public Observable<HttpObject> preInvoke(final Context ctx) {
        if (null != this._stats) {
            final QueryStringDecoder decoder = new QueryStringDecoder(ctx.request().uri());
            this._path = getRawPath(decoder.path());
            ctx.obsRequest().subscribe(RxSubscribers.ignoreNext(),
                    RxSubscribers.ignoreError(),
                    new Action0() {
                        @Override
                        public void call() {
                            _stats.recordExecutedInterval(_path, "-->fullreq", _clock.pauseAndContinue());
                        }});
        }
        
        return null;
    }

    @Override
    public Observable<HttpObject> postInvoke(final Context ctx) {
        if (null != this._stats) {
            final StopWatch respclock = new StopWatch();
            return ctx.obsResponse().doOnNext(new Action1<HttpObject>() {
                @Override
                public void call(final HttpObject hobj) {
                    if (hobj instanceof HttpResponse) {
                        respclock.stopAndRestart();
                    }
                }})
                .doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        _stats.recordExecutedInterval(_path, "<--fullresp", respclock.stopAndRestart());
                        _stats.recordExecutedInterval(_path, "<==>fulltrade", _clock.stopAndRestart());
                        _stats.incExecutedCount(_path);
                    }})
                ;
        } else {
            return null;
        }
    }

    @Inject
    private ApiStats _stats;
    
    private String _path;
    
    private final StopWatch _clock = new StopWatch();
}
