package org.jocean.svr;

import rx.Scheduler;

public interface SchedulerPool {

    public Scheduler selectScheduler();
}
