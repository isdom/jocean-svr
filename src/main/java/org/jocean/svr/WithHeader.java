package org.jocean.svr;

import java.util.Map;

public interface WithHeader {
    public WithHeader setContentDisposition(final String value);
    public String contentDisposition();

    public WithHeader setLocation(final String value);
    public String location();

    public WithHeader setHeader(final String name, final String value);
    public Map<String, String> headers();
}
