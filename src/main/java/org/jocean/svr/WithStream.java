package org.jocean.svr;

import java.io.OutputStream;

public interface WithStream extends WithBody {
    public interface StreamCtrl {

        void onCompleted();

        void onError(Throwable e);

        void onData();
    }

    public String contentType();
    public void onStream(final StreamCtrl ctrl, final OutputStream out);
}
