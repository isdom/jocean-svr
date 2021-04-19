package org.jocean.svr;

import java.io.OutputStream;

public interface WithStream extends WithBody {
    public interface StreamContext {

        void streamCompleted();

        void streamError(Throwable e);

        void chunkReady();

        OutputStream chunkOutput();
    }

    public String contentType();
    public void onStream(final StreamContext sctx);
}
