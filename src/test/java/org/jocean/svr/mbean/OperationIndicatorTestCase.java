package org.jocean.svr.mbean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.jocean.svr.StringTags;
import org.junit.Test;

public class OperationIndicatorTestCase {

    @Test
    public final void testStringTags() {

        assertEquals(new StringTags("1", "2", "3"), new StringTags("1", "2", "3"));
        assertNotEquals(new StringTags("1", "2", "3"), new StringTags("3", "2", "1"));
    }

}
