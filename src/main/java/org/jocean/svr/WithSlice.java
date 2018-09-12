package org.jocean.svr;

import org.jocean.http.ByteBufSlice;

import rx.Observable;

public interface WithSlice extends WithBody {
    public String contentType();
    public Observable<? extends ByteBufSlice> slices();
}
