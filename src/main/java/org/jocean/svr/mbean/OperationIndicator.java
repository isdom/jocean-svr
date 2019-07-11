package org.jocean.svr.mbean;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;

import org.jocean.svr.StringTags;
import org.springframework.beans.factory.annotation.Value;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;

public class OperationIndicator extends NotificationBroadcasterSupport implements OperationIndicatorMXBean {

    OperationIndicator(final RestinIndicator restin, final String operationName, final MeterRegistry meterRegistry) {
        this._restin = restin;
        this._operationName = operationName;
        this._meterRegistry = meterRegistry;

        FunctionCounter.builder("jocean.svr.trade.call", _totalTradeCount, cnt -> cnt.doubleValue())
            .tags(  "operation",    operationName,
                    "hostregex",    _restin.getHostPattern(),
                    "pathregex",    _restin.getPathPattern(),
                    "endpoint_type", _restin.getMbeanName(),
                    "category",     _restin.getCategory(),
                    "priority",     Integer.toString(_restin.getPriority()),
                    "pid",          _restin.getPid(),
                    "port",         Integer.toString(_restin.getPort())
                    )
            .description("The total number of jocean service trade call")
            .register(meterRegistry);

        Gauge.builder("jocean.svr.activetrade", _activeTradeCount, cnt -> cnt.doubleValue())
            .tags(  "operation",    operationName,
                    "hostregex",    _restin.getHostPattern(),
                    "pathregex",    _restin.getPathPattern(),
                    "endpoint_type", _restin.getMbeanName(),
                    "category",     _restin.getCategory(),
                    "priority",     Integer.toString(_restin.getPriority()),
                    "pid",          _restin.getPid(),
                    "port",         Integer.toString(_restin.getPort())
                    )
            .description("The active number of service trade") // optional
            .register(meterRegistry);

        this._durationTimer = Timer.builder("jocean.svr.trade.duration")
            .tags(  "operation",    operationName,
                    "hostregex",    _restin.getHostPattern(),
                    "pathregex",    _restin.getPathPattern(),
                    "endpoint_type", _restin.getMbeanName(),
                    "category",     _restin.getCategory(),
                    "priority",     Integer.toString(_restin.getPriority()),
                    "pid",          _restin.getPid(),
                    "port",         Integer.toString(_restin.getPort())
                    )
            .description("The duration of jocean service trade")
            .publishPercentileHistogram()
            .maximumExpectedValue(Duration.ofSeconds(30))
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
        return _totalTradeCount.get();
    }

    @Override
    public String getOperation() {
        return this._operationName;
    }

    public void incTradeCount(final Action1<Action0> onHalt) {
        this._totalTradeCount.incrementAndGet();

        this._activeTradeCount.incrementAndGet();
        onHalt.call(()-> this._activeTradeCount.decrementAndGet());

        startNotification();
    }

    public void recordTradeDuration(final long durationMillis) {
        this._durationTimer.record(durationMillis, TimeUnit.MILLISECONDS);
    }

    public void recordTradePartDuration(final long durationMillis, final String... tags) {
        getOrCreatePartTimer(tags).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    private Timer getOrCreatePartTimer(final String... tags) {
        final StringTags keyOfTags = new StringTags(tags);

        Timer timer = this._partTimers.get(keyOfTags);

        if (null == timer) {
            timer = Timer.builder("jocean.svr.trade.duration.part")
                .tags(  "operation",    _operationName,
                        "hostregex",    _restin.getHostPattern(),
                        "pathregex",    _restin.getPathPattern(),
                        "endpoint_type", _restin.getMbeanName(),
                        "category",     _restin.getCategory(),
                        "priority",     Integer.toString(_restin.getPriority()),
                        "pid",          _restin.getPid(),
                        "port",         Integer.toString(_restin.getPort())
                        )
                .tags(tags)
                .description("The part duration of jocean service trade")
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(_meterRegistry);

            final Timer old = this._partTimers.putIfAbsent(keyOfTags, timer);
            if (null != old) {
                timer = old;
            }
        }
        return timer;
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
    private final MeterRegistry _meterRegistry;

    private final Timer _durationTimer;

    @Value("${notification.type}")
    private final String _notificationType = "property.changed.operation";

    @Value("${notification.interval}")
    private final long _interval = 10 * 1000;

    private final AtomicBoolean _notifying = new AtomicBoolean(false);

    private long _lastNotifyTimestamp = 0;

    private final AtomicInteger _totalTradeCount = new AtomicInteger(0);
    private final AtomicInteger _activeTradeCount = new AtomicInteger(0);

    private final ConcurrentMap<StringTags, Timer> _partTimers = new ConcurrentHashMap<>();
}
