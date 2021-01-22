package org.jocean.svr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ResponseUtilTestCase {

    @Test
    public final void testRedirectOnly() {
        final String LOCATION = "demourl";
        assertEquals(ResponseUtil.redirectOnly(LOCATION), ResponseUtil.redirectOnly(LOCATION));
    }

}
