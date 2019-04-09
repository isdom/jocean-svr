/**
 *
 */
package org.jocean.svr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.jmx.MBeanRegister;
import org.jocean.idiom.jmx.MBeanRegisterAware;
import org.jocean.j2se.jmx.MBeanUtil;
import org.jocean.svr.mbean.HttpClientMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Subscriber;

/**
 * @author isdom
 *
 */
public class HttpClientDashboard extends Subscriber<HttpInitiator> implements HttpClientMBean, MBeanRegisterAware {

    private static final String OBJECTNAME_SUFFIX = "name=dashboard";

    private static final Logger LOG = LoggerFactory.getLogger(HttpClientDashboard.class);
    @Override
    public void onCompleted() {
        LOG.warn("OnInitiator {} onCompleted", this);
    }

    @Override
    public void onError(final Throwable e) {
        LOG.warn("OnInitiator {} onError, detail:{}",
                this, ExceptionUtils.exception2detail(e));
    }

    @Override
    public void onNext(final HttpInitiator initiator) {
        this._initiators.add(initiator);
        initiator.doOnEnd(()->this._initiators.remove(initiator));
    }

    @Override
    public void setMBeanRegister(final MBeanRegister register) {
        register.registerMBean(OBJECTNAME_SUFFIX, MBeanUtil.createAndConfigureMBean(this));
    }

    @Override
    public String[] getInitiators() {
        final List<String> strs = new ArrayList<>();
        for ( final HttpInitiator initiator : this._initiators) {
            strs.add(initiator.toString());
        }
        return strs.toArray(new String[0]);
    }

    private final Collection<HttpInitiator> _initiators = new ConcurrentLinkedQueue<>();
}
