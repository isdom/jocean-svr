package org.jocean.svr;

import org.jocean.http.ContentDecoder;
import org.jocean.http.InteractBuilder;
import org.jocean.http.WriteCtrl;
import org.jocean.idiom.Endable;

import rx.Observable;

public interface TradeContext {
    public TradeScheduler   scheduler();
    public WriteCtrl        writeCtrl();
    public Endable          endable();
    public AllocatorBuilder allocatorBuilder();
    public InteractBuilder  interactBuilder();
    public <T> Observable<T>  decodeBodyAs(final ContentDecoder decoder, final Class<T> type);
    public <T> Observable<T>  decodeBodyAs(final Class<T> type);
}
