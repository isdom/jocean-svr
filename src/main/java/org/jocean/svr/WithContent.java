package org.jocean.svr;

public interface WithContent extends WithBody {
    public String contentType();
    public Object content();
}
