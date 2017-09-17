package org.jocean.svr;

import java.lang.reflect.Type;

public interface ArgumentBuilder {
    public Object buildArg(final Type argType);
}
