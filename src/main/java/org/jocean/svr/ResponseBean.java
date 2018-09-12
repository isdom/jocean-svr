package org.jocean.svr;

public interface ResponseBean {

    public WithStatus withStatus();
    public WithHeader withHeader();
    public WithBody withBody();
}
