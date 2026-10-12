/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import org.glassfish.jersey.server.ResourceConfig;
import org.junit.Test;

import javax.ws.rs.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Headwind routes MDMesh does not use stay unregistered. Scans exactly as HMDMApplication does
 * ({@code packages("com.hmdm")}), so a resource re-added anywhere under com.hmdm is caught.
 */
public class RetiredRoutesTest {

    private static Set<String> registeredRootPaths() {
        ResourceConfig config = new ResourceConfig().packages("com.hmdm");
        Set<String> paths = new TreeSet<>();
        for (Class<?> c : config.getClasses()) {
            Path p = c.getAnnotation(Path.class);
            if (p != null) {
                paths.add(normalize(p.value()));
            }
        }
        return paths;
    }

    private static String normalize(String path) {
        String s = path.startsWith("/") ? path : "/" + path;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    @Test
    public void scanSeesTheLiveResources() {
        Set<String> paths = registeredRootPaths();
        assertTrue(paths.toString(), paths.contains("/private/web-ui-files"));
        assertTrue(paths.toString(), paths.contains("/public/agent/v1"));
    }

    @Test
    public void legacySelfUpdaterIsGone() {
        assertFalse(registeredRootPaths().contains("/private/update"));
    }

    @Test
    public void legacyConfigFileUploadIsGone() {
        assertFalse(registeredRootPaths().contains("/private/config-files"));
    }

    @Test
    public void legacyDeviceAppUploadIsGone() {
        for (java.lang.reflect.Method m : PublicResource.class.getMethods()) {
            Path p = m.getAnnotation(Path.class);
            assertFalse(m.getName(), p != null && normalize(p.value()).equals("/applications/upload"));
        }
    }

    @Test
    public void legacyVideoRoutesAreGone() {
        assertFalse(registeredRootPaths().contains("/videos"));
    }
}
