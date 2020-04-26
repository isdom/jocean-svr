package org.jocean.svr.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface RpcFacade {
    // pre processors
    String[] value();
    int delay() default 0;
}
