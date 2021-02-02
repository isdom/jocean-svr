package org.jocean.svr;

import org.jocean.http.ContentDecoder;
import org.jocean.http.InteractBuilder;
import org.jocean.http.WriteCtrl;
import org.jocean.idiom.Haltable;
import org.jocean.svr.mbean.RestinIndicatorMXBean;

import rx.Observable;

public interface TradeContext {
    public TradeScheduler   scheduler();
    public WriteCtrl        writeCtrl();
    public Haltable         haltable();
    public AllocatorBuilder allocatorBuilder();
    public InteractBuilder  interactBuilder();
//    public void             enableRepeatDecode();
    public <T> Observable<T>  decodeBodyAs(final ContentDecoder decoder, final Class<T> type);
    public <T> Observable<T>  decodeBodyAs(final Class<T> type);
    public RestinIndicatorMXBean restin();
}
