package org.jocean.svr.interceptor;

import java.lang.reflect.Type;

import javax.inject.Inject;

import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxSubscribers;
import org.jocean.j2se.stats.ApiStats;
import org.jocean.svr.ArgumentBuilder;
import org.jocean.svr.MethodInterceptor;
import org.jocean.svr.ProcessMemo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.QueryStringDecoder;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;

public class StatRest implements MethodInterceptor, ArgumentBuilder {
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
                            _stats.recordExecutedInterval(_path, "__req", _clock.pauseAndContinue());
                        }});
        }
        
        return null;
    }

    @Override
    public Object buildArg(final Type argType) {
        if (ProcessMemo.class.equals(argType)) {
            return new ProcessMemo() {
                @Override
                public void setEndreason(final String endreason) {
                    _endreason = endreason;
                }

                @Override
                public void markDurationAs(final String stage) {
                    _stats.recordExecutedInterval(_path, stage, _processclock.stopAndRestart());
                }
            };
        } else {
            return null;
        }
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
                        final String er = _endreason;
                        _stats.recordExecutedInterval(_path, "__resp", respclock.stopAndRestart());
                        _stats.recordExecutedInterval(_path, "_whole_." + er, _clock.stopAndRestart());
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
    
    private volatile String _endreason = "default";
    
    private final StopWatch _clock = new StopWatch();
    private final StopWatch _processclock = new StopWatch();
}
