package com.hmdm.rest.resource.support;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.ManagedAppPermission;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.DesiredConfigBuilder;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/** Ensures a pre-runtime-permissions agent cannot acknowledge a document section it never received. */
public class ConfigReconcilerPermissionCapabilityTest {
    private static final class FakeUnsecureDAO extends UnsecureDAO {
        final Configuration configuration;
        final List<Application> apps;
        final List<ManagedAppPermission> permissions;

        FakeUnsecureDAO(Configuration configuration, List<Application> apps, List<ManagedAppPermission> permissions) {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    "/tmp", 0, "com.mdmesh.agent");
            this.configuration = configuration;
            this.apps = apps;
            this.permissions = permissions;
        }

        @Override public Configuration getConfigurationById(Integer id) { return configuration; }
        @Override public List<Application> getPlainConfigurationAppsOptimized(Integer id) { return apps; }
        @Override public List<ManagedAppPermission> getManagedAppPermissions(Integer id) { return permissions; }
    }

    @Test
    public void omitsPermissionRulesAndUsesDifferentRevisionForAnOlderAgent() {
        Configuration configuration = new Configuration();
        configuration.setId(7);
        configuration.setCustomerId(1);
        Application app = new Application();
        app.setId(4); app.setPkg("org.example.camera"); app.setAction(DesiredConfigBuilder.ACTION_INSTALL);
        ManagedAppPermission rule = new ManagedAppPermission();
        rule.setApplicationId(4); rule.setPermission("android.permission.CAMERA"); rule.setState("deny");
        Device device = new Device(); device.setConfigurationId(7);
        ConfigReconciler reconciler = new ConfigReconciler(
                new FakeUnsecureDAO(configuration, Collections.singletonList(app), Collections.singletonList(rule)),
                null, Fakes.noExtensionPoints());

        DesiredConfig capable = reconciler.currentDocument(device,
                Collections.singleton(DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY));
        DesiredConfig older = reconciler.currentDocument(device, Collections.<String>emptySet());

        assertEquals(1, capable.getAppPermissions().size());
        assertNull(older.getAppPermissions());
        assertNotEquals(capable.getRevision(), older.getRevision());
    }
}
