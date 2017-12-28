package org.jocean.svr;

import org.jocean.http.WritePolicy;

public interface WritePolicyAware {
    public void setWritePolicy(final WritePolicy writePolicy);
}
