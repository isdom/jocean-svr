package org.jocean.svr.interceptor;

import java.lang.reflect.Type;

import javax.inject.Inject;

import org.jocean.idiom.StopWatch;
import org.jocean.idiom.rx.RxSubscribers;
import org.jocean.j2se.stats.ApiStats;
import org.jocean.svr.ArgumentBuilder;
import org.jocean.svr.MethodInterceptor;
import org.jocean.svr.ProcessMemo;
import org.jocean.svr.SvrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;

public class StatRest implements MethodInterceptor, ArgumentBuilder {
    private static final Logger LOG
        = LoggerFactory.getLogger(StatRest.class);

//    private static String getRawPath(final String path) {
//        if (path.startsWith("http://") || path.startsWith("https://")) {
//            //  http://wangsz.xicp.net:10900/call/zmccCallEndNotify
//            final int schemeIdx = path.indexOf("://");
//            final String pathWithoutScheme = path.substring(schemeIdx + 3);
//            final int rawPathIdx = pathWithoutScheme.indexOf('/');
//            return (rawPathIdx > 0) ? pathWithoutScheme.substring(rawPathIdx) : "/";
//        } else {
//            return path;
//        }
//    }

    @Override
    public Observable<? extends Object> preInvoke(final Context ctx) {
        if (null != this._stats) {
            // getRawPath(new QueryStringDecoder(ctx.request().uri()).path());
            this._path = SvrUtil.genMethodPathOf(SvrUtil.getPathOfClass(ctx.resource().getClass()), ctx.processor());
            ctx.obsRequest().subscribe(RxSubscribers.ignoreNext(), RxSubscribers.ignoreError(),
                    () -> _stats.recordExecutedInterval(this._path, "__req", _clock.pauseAndContinue()));
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
                    if (null != _stats) {
                        _stats.recordExecutedInterval(_path, stage, _processclock.stopAndRestart());
                    } else {
                        LOG.warn("can not mark duration, cause missing ApiStats instance!\r\n\tforget add unit/apistats.xml module ?");
                    }
                }
            };
        } else {
            return null;
        }
    }

    @Override
    public Observable<? extends Object> postInvoke(final Context ctx) {
        if (null != this._stats) {
            final StopWatch respclock = new StopWatch();
            return ctx.obsResponse().doOnNext(obj -> {
                    if (obj instanceof HttpResponse) {
                        respclock.stopAndRestart();
                    }
                }).doOnCompleted(() -> {
                    final String er = _endreason;
                    _stats.recordExecutedInterval(this._path, "__resp", respclock.stopAndRestart());
                    _stats.recordExecutedInterval(this._path, "_whole_." + er, _clock.stopAndRestart());
                    _stats.incExecutedCount(this._path);
                });
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
