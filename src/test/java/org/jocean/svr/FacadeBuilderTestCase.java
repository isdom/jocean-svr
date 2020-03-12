package org.jocean.svr;

import static org.junit.Assert.assertNotNull;

import org.jocean.http.RpcExecutor;
import org.junit.Test;

public class FacadeBuilderTestCase {

    @Test
    public final void testBuild() {
        final FacadeBuilder fbi = new FacadeBuilder() {
            @Override
            public <F> F build(final Class<F> facadeType, final String... preprocessors) {
                System.out.println(preprocessors.length);
                assertNotNull(preprocessors);
                return null;
            }

            @Override
            public <F> F build(final Class<F> facadeType, final RpcExecutor executor, final String... preprocessors) {
                return null;
            }};

       fbi.build(Integer.class);
    }

}
