package org.jocean.svr.mbean;

import java.util.concurrent.atomic.AtomicInteger;

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

import io.netty.channel.ServerChannel;

public class RestinIndicator extends InboundIndicator
    implements RestinMXBean, ServerChannelAware, MBeanRegisterAware, NotificationEmitter {

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

    public void incTradeCount() {
        this._tradeCount.incrementAndGet();
        _notificationBroadcasterSupport.sendNotification(new Notification(_notificationType, this, 0));
    }

    private MBeanRegister _register;

    private String _mbeanName;
    private String _pathPattern;
    private String _hostPattern;
    private String _category;
    private int _priority;

    @Value("${notification.type}")
    private final String _notificationType = "property.changed.restin";

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
}
