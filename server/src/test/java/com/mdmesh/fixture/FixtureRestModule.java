package com.mdmesh.fixture;

import com.google.inject.servlet.ServletModule;
import com.hmdm.plugin.rest.PluginAccessFilter;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.rest.filter.PrivateIPFilter;
import com.hmdm.rest.filter.PublicIPFilter;
import com.hmdm.security.jwt.JWTFilter;

import java.util.Arrays;
import java.util.List;

/**
 * The fixture plugin's REST filters, as web/EXTENDING.md's REST row lays a fork's out (messaging's recipe).
 * PrivateRouteFilterTest checks that its private route goes through them.
 */
public class FixtureRestModule extends ServletModule {

    private static final List<String> protectedResources = Arrays.asList("/rest/plugins/fixture/private/*");
    private static final List<String> publicResources = Arrays.asList("/rest/plugins/fixture/public/*");

    @Override
    protected void configureServlets() {
        filter(protectedResources).through(JWTFilter.class);
        filter(protectedResources).through(AuthFilter.class);
        filter(protectedResources).through(PluginAccessFilter.class);
        filter(protectedResources).through(PrivateIPFilter.class);
        filter(publicResources).through(PublicIPFilter.class);
    }
}
