package org.jocean.svr.mbean;

import org.jocean.http.server.mbean.InboundMXBean;

public interface RestinMXBean extends InboundMXBean {

    public String getCategory();

    public String getPathPattern();

    public String getHostPattern();

    public int getPriority();

    public String getPid();

    public int getTradeCount();
}
