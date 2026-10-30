package com.hmdm.plugin;

import org.junit.Test;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the ClassGraph classpath scan in {@link PluginList#init}: with the same bundled plugin jars as launcher.war on
 * the classpath, all of them must be discovered and yield Guice modules. Catches a ClassGraph upgrade/API change that
 * silently finds nothing (the server would boot with zero plugins). PluginList keeps static state and initialises once
 * per JVM, so any other server test that calls init shares this result.
 */
public class PluginDiscoveryTest {

    /** The context the plugins read their settings from at init: only devicelog's persistence choice. */
    public static ServletContext context() {
        return (ServletContext) Proxy.newProxyInstance(
                PluginDiscoveryTest.class.getClassLoader(), new Class<?>[]{ServletContext.class},
                (proxy, method, args) -> {
                    if ("getInitParameter".equals(method.getName())
                            && "plugin.devicelog.persistence.config.class".equals(args[0])) {
                        return "com.hmdm.plugins.devicelog.persistence.postgres.DeviceLogPostgresPersistenceConfiguration";
                    }
                    return null;
                });
    }

    @Test
    public void scanFindsEveryBundledPlugin() {
        PluginList.init(context());

        // fixture: the tests' own fork plugin under com.mdmesh (com.mdmesh.fixture), on this classpath only. Contained,
        // not equal: a fork's plugins in plugins/fork are on this classpath too, and must not fail core's build.
        List<String> expected = Arrays.asList("audit", "deviceinfo", "devicelog", "messaging", "push", "xtra", "fixture");
        TreeSet<String> found = new TreeSet<>(PluginList.getEnabledPlugins());
        assertTrue("core's plugins " + expected + " are all among " + found, found.containsAll(expected));
        assertFalse("plugins contributed no Guice modules", PluginList.getPluginModules().isEmpty());
    }
}
