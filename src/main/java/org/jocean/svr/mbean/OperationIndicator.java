package org.jocean.svr.mbean;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;

import org.springframework.beans.factory.annotation.Value;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import rx.Observable;

public class OperationIndicator extends NotificationBroadcasterSupport implements OperationIndicatorMXBean {

    OperationIndicator(final RestinIndicator restin, final String operationName, final MeterRegistry meterRegistry) {
        this._restin = restin;
        this._operationName = operationName;

        FunctionCounter.builder("jocean.svr.operation.call", _tradeCount, cnt -> cnt.doubleValue())
            .tags(  "operation", operationName,
                    "host", _restin.getHostPattern(),
                    "path", _restin.getPathPattern(),
                    "priority", Integer.toString(_restin.getPriority()),
                    "pid", _restin.getPid(),
                    "port", Integer.toString(_restin.getPort()),
                    "category", _restin.getCategory()
                    )
            .description("The total number of jocean service operation's call")
            .baseUnit("calls")
            .register(meterRegistry);
    }

    @Override
    public String getCategory() {
        return _restin.getCategory();
    }

    @Override
    public String getPathPattern() {
        return _restin.getPathPattern();
    }

    @Override
    public String getHostPattern() {
        return _restin.getHostPattern();
    }

    @Override
    public int getPriority() {
        return _restin.getPriority();
    }

    @Override
    public String getPid() {
        return _restin.getPid();
    }

    @Override
    public String getHost() {
        return _restin.getHost();
    }

    @Override
    public String getHostIp() {
        return _restin.getHostIp();
    }

    @Override
    public String getBindIp() {
        return _restin.getBindIp();
    }

    @Override
    public int getPort() {
        return _restin.getPort();
    }

    @Override
    public int getTradeCount() {
        return _tradeCount.get();
    }

    @Override
    public String getOperation() {
        return this._operationName;
    }

    public void incTradeCount() {
        this._tradeCount.incrementAndGet();

        startNotification();
    }

    private void startNotification() {
        if (_notifying.compareAndSet(false, true)) {
            // get notify's permission
            final long now = System.currentTimeMillis();
            if (now - _lastNotifyTimestamp > _interval ) {
                // notify now
                doNotification(now);
            }
            else {
                Observable.timer(_lastNotifyTimestamp + _interval - now, TimeUnit.MILLISECONDS)
                    .subscribe(ms -> doNotification(System.currentTimeMillis()));
            }
        }
    }

    private void doNotification(final long now) {
        try {
            this.sendNotification(new Notification(_notificationType, this, 0));
        } finally {
            _lastNotifyTimestamp = now;
            _notifying.set(false);
        }
    }

    private final RestinIndicator _restin;
    private final String _operationName;

    @Value("${notification.type}")
    private final String _notificationType = "property.changed.operation";

    @Value("${notification.interval}")
    private final long _interval = 10 * 1000;

    private final AtomicBoolean _notifying = new AtomicBoolean(false);

    private long _lastNotifyTimestamp = 0;

    private final AtomicInteger _tradeCount = new AtomicInteger(0);
}
