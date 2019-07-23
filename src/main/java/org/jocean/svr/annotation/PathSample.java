package org.jocean.svr.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import com.google.common.annotations.Beta;

@Beta
@Retention(RetentionPolicy.RUNTIME)
public @interface PathSample {
    String value();
}
