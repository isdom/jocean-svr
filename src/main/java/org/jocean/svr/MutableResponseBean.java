package org.jocean.svr;

public interface MutableResponseBean extends ResponseBean {

    public MutableResponseBean setStatus(int status);
    public MutableResponseBean setHeader(WithHeader withHeader);
    public MutableResponseBean setBody(WithBody withBody);
}
