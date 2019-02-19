package org.jocean.svr;

import rx.Scheduler;

public interface TradeScheduler {

    public Scheduler scheduler();

    public int workerCount();
}
