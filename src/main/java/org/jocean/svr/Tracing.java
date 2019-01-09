package org.jocean.svr;

public interface Tracing {

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public Scope activate();
}
