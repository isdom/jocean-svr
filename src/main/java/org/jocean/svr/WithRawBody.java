package org.jocean.svr;

import org.jocean.http.MessageBody;

import rx.Observable;

public interface WithRawBody extends WithBody {
    public Observable<? extends MessageBody> body();
}
