package org.jocean.svr.tracing;

import org.aspectj.lang.ProceedingJoinPoint;
import org.jocean.svr.Tracing;
import org.jocean.svr.Tracing.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//该类为切面
public class TracingAspect {
    private static final Logger LOG = LoggerFactory.getLogger(TracingAspect.class);

    public void around(final ProceedingJoinPoint pjp) throws Throwable {
        final Object[] args = pjp.getArgs();
        if (args.length > 0 && args[0] instanceof Tracing) {
            final Tracing tracing = (Tracing) args[0];
            try (final Scope scope = tracing.activate()) {
                pjp.proceed();
            }
        } else {
            pjp.proceed();
        }
    }
}