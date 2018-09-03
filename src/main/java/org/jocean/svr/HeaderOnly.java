package org.jocean.svr;

public class HeaderOnly implements WithContent {
    @Override
    public Object content() {
        return null;
    }
    @Override
    public String contentType() {
        return null;
    }
}
