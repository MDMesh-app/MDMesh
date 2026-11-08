package com.hmdm;

import com.hmdm.plugin.PluginList;
import com.hmdm.rest.filter.PrivateRouteGuard;
import com.hmdm.rest.resource.PasswordResetResource;
import com.mdmesh.fixture.FixturePrivateResource;
import com.mdmesh.fixture.FixturePublicResource;
import org.junit.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Jersey serves only the resource classes {@link HMDMApplication} registers. A fork plugin's REST lives under
 * {@code com.mdmesh} (web/EXTENDING.md), so the application must scan that package as well as core's {@code com.hmdm}:
 * otherwise the plugin installs, its filters run, and every one of its routes answers 404.
 */
public class HMDMApplicationTest {

    @Test
    public void registersAForkPluginsPrivateAndPublicResources() {
        Set<Class<?>> classes = new HMDMApplication(null).getClasses();

        assertTrue("com.mdmesh private resource registered", classes.contains(FixturePrivateResource.class));
        assertTrue("com.mdmesh public resource registered", classes.contains(FixturePublicResource.class));
        assertTrue("core's com.hmdm resources still registered", classes.contains(PasswordResetResource.class));
        assertTrue("the private-route guard registered", classes.contains(PrivateRouteGuard.class));
    }

    @Test
    public void swaggerDocumentsThePluginScansPackages() {
        assertEquals(Arrays.asList("com.hmdm", "com.mdmesh"), PluginList.PACKAGES);
        // BeanConfig.setResourcePackage takes a comma-separated list.
        assertEquals(PluginList.PACKAGES, Arrays.asList(HMDMApplication.swaggerResourcePackage().split(",")));
    }
}
