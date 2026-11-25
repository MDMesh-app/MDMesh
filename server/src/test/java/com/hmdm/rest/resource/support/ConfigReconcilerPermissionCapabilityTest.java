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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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

    private static ConfigReconciler reconciler(List<ManagedAppPermission> rules) {
        Configuration configuration = new Configuration();
        configuration.setId(7);
        configuration.setCustomerId(1);
        Application app = new Application();
        app.setId(4); app.setPkg("org.example.camera"); app.setAction(DesiredConfigBuilder.ACTION_INSTALL);
        return new ConfigReconciler(new FakeUnsecureDAO(configuration, Collections.singletonList(app), rules),
                null, Fakes.noExtensionPoints());
    }

    private static Device device() {
        Device device = new Device(); device.setConfigurationId(7);
        return device;
    }

    private static final Set<String> OLD_AGENT = Collections.singleton(DesiredConfigBuilder.CAPABILITY);
    private static final Set<String> NEW_AGENT = new java.util.HashSet<String>(java.util.Arrays.asList(
            DesiredConfigBuilder.CAPABILITY, DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY));

    /** A server update must not show every older agent out of sync: a configuration without rules asks nothing new. */
    @Test
    public void anOlderAgentIsInSyncWithAConfigurationWithoutRules() {
        ConfigReconciler reconciler = reconciler(Collections.<ManagedAppPermission>emptyList());
        String sentToOldAgent = reconciler.currentDocument(device(), OLD_AGENT).getRevision();
        assertEquals(sentToOldAgent, reconciler.currentRevision(device(), OLD_AGENT));
        String sentToNewAgent = reconciler.currentDocument(device(), NEW_AGENT).getRevision();
        assertEquals(sentToNewAgent, reconciler.currentRevision(device(), NEW_AGENT));
        // The new agent gets the explicit empty section (it releases earlier rules), under its own revision.
        assertNotEquals(sentToOldAgent, sentToNewAgent);
    }

    /** An older agent can't enforce the rules: it applies the rest, and never shows in sync. */
    @Test
    public void anOlderAgentIsNeverInSyncWithAConfigurationWithRules() {
        ManagedAppPermission rule = new ManagedAppPermission();
        rule.setApplicationId(4); rule.setPermission("android.permission.CAMERA"); rule.setState("deny");
        ConfigReconciler reconciler = reconciler(Collections.singletonList(rule));
        String sentToOldAgent = reconciler.currentDocument(device(), OLD_AGENT).getRevision();
        assertNotEquals(sentToOldAgent, reconciler.currentRevision(device(), OLD_AGENT));
        assertEquals(reconciler.currentDocument(device(), NEW_AGENT).getRevision(),
                reconciler.currentRevision(device(), NEW_AGENT));
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

    /**
     * Whatever else an agent advertises (nothing, config.* tokens of another release's sections), it is sent the whole
     * document; without the rules, byte for byte and revision included, when it lacks their capability.
     */
    @Test
    public void onlyTheCapabilityDecidesWhatIsSent() {
        ManagedAppPermission rule = new ManagedAppPermission();
        rule.setApplicationId(4); rule.setPermission("android.permission.CAMERA"); rule.setState("deny");
        for (List<ManagedAppPermission> rules : Arrays.asList(Collections.singletonList(rule),
                Collections.<ManagedAppPermission>emptyList(), null)) {
            String full = payload(allSections(rules).currentDocument(device()));
            String withoutRules = payload(allSections(null).currentDocument(device()));
            for (Set<String> other : Arrays.asList(Collections.<String>emptySet(), OLD_AGENT,
                    Collections.singleton("config.policies"), Collections.singleton("config.none"),
                    new HashSet<String>(Arrays.asList("config.appPermissions", "config.kiosk")))) {
                assertEquals(withoutRules, payload(allSections(rules).currentDocument(device(), other)));
                Set<String> capable = new HashSet<String>(other);
                capable.add(DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY);
                assertEquals(full, payload(allSections(rules).currentDocument(device(), capable)));
            }
            assertEquals(withoutRules, payload(allSections(rules).currentDocument(device(), null)));
        }
    }

    private static ConfigReconciler allSections(List<ManagedAppPermission> rules) {
        Configuration configuration = new Configuration();
        configuration.setId(7);
        configuration.setCustomerId(1);
        configuration.setKioskMode(true);
        configuration.setCamera(false);
        configuration.setConnectivityMode(DesiredConfigBuilder.CONNECTIVITY_ALWAYS_ON);
        Application app = new Application();
        app.setId(4); app.setPkg("org.example.camera"); app.setAction(DesiredConfigBuilder.ACTION_INSTALL);
        return new ConfigReconciler(new FakeUnsecureDAO(configuration, Collections.singletonList(app), rules),
                null, Fakes.noExtensionPoints());
    }

    private static String payload(DesiredConfig doc) { return DesiredConfigBuilder.toPayloadJson(doc); }
}
