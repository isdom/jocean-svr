package org.jocean.svr;

import org.jocean.http.ByteBufSlice;

import rx.Observable;

public interface WithSlice {
    public String contentType();
    public Observable<ByteBufSlice> slices();
}
