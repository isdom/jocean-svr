package org.jocean.svr;

public interface TrafficRecorder {
    public void record(final long inboundBytes, final long outboundBytes, final String...tags);
}
