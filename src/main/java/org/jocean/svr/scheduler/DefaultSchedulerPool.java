package org.jocean.svr.scheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.jocean.svr.SchedulerPool;
import org.springframework.beans.factory.annotation.Value;

import io.micrometer.core.instrument.MeterRegistry;
import rx.Scheduler;
import rx.schedulers.Schedulers;

public class DefaultSchedulerPool implements SchedulerPool {

    @Override
    public Scheduler selectScheduler() {
        return _schedulers[_nextSchedulerIdx.getAndIncrement() % _schedulers.length];
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DefaultSchedulerPool [workerCount=").append(_workerCount).append(", threadName=")
                .append(_threadName).append("]");
        return builder.toString();
    }

    static class DefaultThreadFactory implements ThreadFactory {
        private final ThreadGroup _group;
        private final AtomicInteger _threadNumber = new AtomicInteger(1);
        private final String _namePrefix;

        DefaultThreadFactory(final String prefix) {
            final SecurityManager s = System.getSecurityManager();
            _group = (s != null) ? s.getThreadGroup() :
                                  Thread.currentThread().getThreadGroup();
            _namePrefix = prefix + "-";
        }

        @Override
        public Thread newThread(final Runnable r) {
            final Thread t = new Thread(_group, r,
                                  _namePrefix + _threadNumber.getAndIncrement(),
                                  0);
            if (t.isDaemon())
                t.setDaemon(false);
            if (t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    void start() {
        final ThreadFactory tf = new DefaultThreadFactory(_threadName);

        this._executors = new ExecutorService[this._workerCount];
        this._schedulers = new Scheduler[this._workerCount];
        for (int idx = 0; idx < this._executors.length; idx++) {
            this._executors[idx] = Executors.newSingleThreadExecutor(tf);
            this._schedulers[idx] = Schedulers.from(this._executors[idx]);
        }

//        if (null != this._meterRegistry) {
//            new ExecutorServiceMetrics(this._workers, this._threadName, Collections.emptyList()).bindTo(_meterRegistry);
//        }
    }

    void stop() {
        for (final ExecutorService executor : this._executors) {
            executor.shutdown();
        }
    }

    public int workerCount() {
        return this._workerCount;
    }

    @Value("${worker.count}")
    public void setWorkerCount(final int workerCount) {
        this._workerCount = workerCount > 0 ? workerCount : Runtime.getRuntime().availableProcessors() * 2;
    }

    int _workerCount = Runtime.getRuntime().availableProcessors() * 2;

    @Value("${thread.name}")
    String _threadName = "trade-executor";

    ExecutorService[] _executors;
    Scheduler[] _schedulers;
    final AtomicInteger _nextSchedulerIdx = new AtomicInteger(0);

    @Inject
    MeterRegistry _meterRegistry;
}
