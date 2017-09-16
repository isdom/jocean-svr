package org.jocean.svr;

import io.netty.handler.codec.http.FullHttpRequest;
import rx.functions.Action1;

public interface MessageDecoder {
    
    public void visitFullRequest(final Action1<FullHttpRequest> visitor);
    
    public <T> T decodeJsonAs(final Class<T> type);
    
    public <T> T decodeXmlAs(final Class<T> type);
    
    public <T> T decodeFormAs(final Class<T> type);
}
