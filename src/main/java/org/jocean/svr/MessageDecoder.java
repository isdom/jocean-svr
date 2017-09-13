package org.jocean.svr;

public interface MessageDecoder {
    
    public <T> T decodeAsJson(final Class<T> type);
    
    public <T> T decodeAsXml(final Class<T> type);
    
    public <T> T decodeAsForm(final Class<T> type);
}
