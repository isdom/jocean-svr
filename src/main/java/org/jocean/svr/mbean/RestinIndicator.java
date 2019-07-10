package org.jocean.svr.mbean;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanNotificationInfo;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;

import org.jocean.http.server.mbean.InboundIndicator;
import org.jocean.http.util.Nettys.ServerChannelAware;
import org.jocean.idiom.jmx.MBeanRegister;
import org.jocean.idiom.jmx.MBeanRegisterAware;
import org.jocean.j2se.os.OSUtil;
import org.springframework.beans.factory.annotation.Value;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.ServerChannel;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;

public class RestinIndicator extends InboundIndicator
    implements RestinIndicatorMXBean, ServerChannelAware, MBeanRegisterAware, NotificationEmitter {

    @Override
    public String getPid() {
        return Integer.toString(OSUtil.getCurrentPid());
    }

    @Override
    public String getPathPattern() {
        return this._pathPattern;
    }

    @Override
    public String getHostPattern() {
        return this._hostPattern;
    }

    @Override
    public String getCategory() {
        return this._category;
    }

    @Override
    public int getPriority() {
        return this._priority;
    }

    public String getMbeanName() {
        return this._mbeanName;
    }

    @Override
    public void setServerChannel(final ServerChannel serverChannel) {
        super.setServerChannel(serverChannel);
        this._register.registerMBean("name="+this._mbeanName+",address=" + this.getBindIp().replace(':', '_')
                +",port=" + this.getPort(), this);
    }

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        this._register = register;
    }

    public void setMbeanName(final String mbeanName) {
        this._mbeanName = mbeanName;
    }

    public void setPathPattern(final String pathPattern) {
        this._pathPattern = pathPattern;
    }

    public void setHostPattern(final String hostPattern) {
        this._hostPattern = hostPattern;
    }

    public void setCategory(final String category) {
        this._category = category;
    }

    public void setPriority(final int priority) {
        this._priority = priority;
    }

    @Override
    public int getTradeCount() {
        return _tradeCount.get();
    }

    public void incTradeCount(final String operationName, final Action1<Action0> onHalt) {
        this._tradeCount.incrementAndGet();

        startNotification();

        getOrCreateOperationInd(operationName).incTradeCount(onHalt);
    }

    public void recordTradeDuration(final String operationName, final long durationMillis) {
        getOrCreateOperationInd(operationName).recordTradeDuration(durationMillis);
    }

    public void recordTradePartDuration(final String operationName, final long durationMillis, final String... tags) {
        getOrCreateOperationInd(operationName).recordTradePartDuration(durationMillis, tags);
    }

    private OperationIndicator getOrCreateOperationInd(final String operationName) {
        OperationIndicator ind = this._operationInds.get(operationName);

        if (null == ind) {
            ind = new OperationIndicator(this, operationName, _meterRegistry);
            final OperationIndicator old = this._operationInds.putIfAbsent(operationName, ind);
            if (null != old) {
                ind = old;
            }
            else {
                // register OperationIndicator mbean
                this._register.registerMBean("endpoint_type="+this._mbeanName+",bindip=" + this.getBindIp().replace(':', '_')
                        +",port=" + this.getPort() + ",operation=" + operationName, ind);
            }
        }
        return ind;
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
            _notificationBroadcasterSupport.sendNotification(new Notification(_notificationType, this, 0));
        } finally {
            _lastNotifyTimestamp = now;
            _notifying.set(false);
        }
    }

    @Inject
    MeterRegistry _meterRegistry = Metrics.globalRegistry;

    private MBeanRegister _register;

    private String _mbeanName;
    private String _pathPattern;
    private String _hostPattern;
    private String _category;
    private int _priority;

    @Value("${notification.type}")
    private final String _notificationType = "property.changed.restin";

    @Value("${notification.interval}")
    private final long _interval = 30 * 1000;

    private final AtomicBoolean _notifying = new AtomicBoolean(false);

    private long _lastNotifyTimestamp = 0;

    private final AtomicInteger _tradeCount = new AtomicInteger(0);

    private final NotificationBroadcasterSupport _notificationBroadcasterSupport = new NotificationBroadcasterSupport();

    @Override
    public void addNotificationListener(final NotificationListener listener, final NotificationFilter filter, final Object handback)
            throws IllegalArgumentException {
        _notificationBroadcasterSupport.addNotificationListener(listener, filter, handback);
    }

    @Override
    public void removeNotificationListener(final NotificationListener listener) throws ListenerNotFoundException {
        _notificationBroadcasterSupport.removeNotificationListener(listener);
    }

    @Override
    public MBeanNotificationInfo[] getNotificationInfo() {
        return _notificationBroadcasterSupport.getNotificationInfo();
    }

    @Override
    public void removeNotificationListener(final NotificationListener listener, final NotificationFilter filter, final Object handback)
            throws ListenerNotFoundException {
        _notificationBroadcasterSupport.removeNotificationListener(listener, filter, handback);
    }

    private final ConcurrentMap<String, OperationIndicator> _operationInds = new ConcurrentHashMap<>();
}
