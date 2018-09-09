package org.jocean.svr;

import org.jocean.http.InteractBuilder;
import org.jocean.http.WriteCtrl;
import org.jocean.idiom.Terminable;

public interface TradeContext {
    public WriteCtrl        writeCtrl();
    public Terminable       terminable();
    public AllocatorBuilder allocatorBuilder();
    public InteractBuilder  interactBuilder();
}
