package org.jocean.svr;

import java.lang.reflect.Method;

import javax.ws.rs.Path;

public class SvrUtil {
    public static String getPathOfClass(final Class<?> resourceCls) {
        final Path path = resourceCls.getAnnotation(Path.class);
        return null != path ? path.value() : "";
    }

    public static String genMethodPathOf(final String rootPath, final Method method) {
        final Path methodPath = method.getAnnotation(Path.class);

        if (null != methodPath) {
            return rootPath + methodPath.value();
        } else {
            return rootPath;
        }
    }

}
