package org.jocean.svr;

import org.jocean.http.RpcExecutor;
import org.jocean.j2se.tracing.Tracing;

import io.opentracing.Span;
import rx.Scheduler;

@Deprecated
/**
 * replace by @RpcScope
 * @author isdom
 *
 */
public interface Branch {
    public Span span();
    public Tracing tracing();
    public RpcExecutor rpcExecutor();
    public Scheduler scheduler();

    public interface Builder {
        public Branch buildFollowsFrom(final String branchName);
    }
}
