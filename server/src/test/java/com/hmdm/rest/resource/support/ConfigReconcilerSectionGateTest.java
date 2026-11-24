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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** A device gets only the configuration sections its agent applies, and is in sync only when it applies all it is asked. */
public class ConfigReconcilerSectionGateTest {
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

    private static final String CURRENT = "0.5.1";
    private static final Set<String> OLD_AGENT = Collections.singleton(DesiredConfigBuilder.CAPABILITY);
    private static final Set<String> NEW_AGENT = new java.util.HashSet<String>(java.util.Arrays.asList(
            DesiredConfigBuilder.CAPABILITY, DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY));

    /** A server update must not show every older agent out of sync: a configuration without rules asks nothing new. */
    @Test
    public void anOlderAgentIsInSyncWithAConfigurationWithoutRules() {
        ConfigReconciler reconciler = reconciler(Collections.<ManagedAppPermission>emptyList());
        String sentToOldAgent = reconciler.currentDocument(device(), OLD_AGENT, CURRENT).getRevision();
        assertEquals(sentToOldAgent, reconciler.currentRevision(device(), OLD_AGENT, CURRENT));
        String sentToNewAgent = reconciler.currentDocument(device(), NEW_AGENT, CURRENT).getRevision();
        assertEquals(sentToNewAgent, reconciler.currentRevision(device(), NEW_AGENT, CURRENT));
        // The new agent gets the explicit empty section (it releases earlier rules), under its own revision.
        assertNotEquals(sentToOldAgent, sentToNewAgent);
    }

    /** An older agent can't enforce the rules: it applies the rest, and never shows in sync. */
    @Test
    public void anOlderAgentIsNeverInSyncWithAConfigurationWithRules() {
        ManagedAppPermission rule = new ManagedAppPermission();
        rule.setApplicationId(4); rule.setPermission("android.permission.CAMERA"); rule.setState("deny");
        ConfigReconciler reconciler = reconciler(Collections.singletonList(rule));
        String sentToOldAgent = reconciler.currentDocument(device(), OLD_AGENT, CURRENT).getRevision();
        assertNotEquals(sentToOldAgent, reconciler.currentRevision(device(), OLD_AGENT, CURRENT));
        assertEquals(reconciler.currentDocument(device(), NEW_AGENT, CURRENT).getRevision(),
                reconciler.currentRevision(device(), NEW_AGENT, CURRENT));
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
                Collections.singleton(DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY), CURRENT);
        DesiredConfig older = reconciler.currentDocument(device, Collections.<String>emptySet(), CURRENT);

        assertEquals(1, capable.getAppPermissions().size());
        assertNull(older.getAppPermissions());
        assertNotEquals(capable.getRevision(), older.getRevision());
    }

    /** A configuration that sets connectivity (since 0.4.0), and nothing gated by a capability. */
    private static ConfigReconciler withConnectivity() {
        Configuration configuration = new Configuration();
        configuration.setId(7);
        configuration.setCustomerId(1);
        configuration.setConnectivityMode(DesiredConfigBuilder.CONNECTIVITY_ALWAYS_ON);
        return new ConfigReconciler(new FakeUnsecureDAO(configuration, Collections.<Application>emptyList(), null),
                null, Fakes.noExtensionPoints());
    }

    /** An agent before 0.4.0 ignores connectivity: it gets the rest, and is not in sync while the configuration sets it. */
    @Test
    public void anAgentBeforeASectionsReleaseIsNotInSyncWhenTheConfigurationSetsIt() {
        ConfigReconciler reconciler = withConnectivity();
        DesiredConfig sent = reconciler.currentDocument(device(), OLD_AGENT, "0.3.8");
        assertNull(sent.getConnectivity());
        assertNotEquals("it reports the revision of what it got", sent.getRevision(),
                reconciler.currentRevision(device(), OLD_AGENT, "0.3.8"));
        DesiredConfig full = reconciler.currentDocument(device(), OLD_AGENT, "0.4.0");
        assertEquals(DesiredConfigBuilder.CONNECTIVITY_ALWAYS_ON, full.getConnectivity().getMode());
        assertEquals(full.getRevision(), reconciler.currentRevision(device(), OLD_AGENT, "0.4.0"));
    }

    /**
     * The fleet's version strings. A section left out is released by the agent, so only a version that reads as one,
     * below the section's release, leaves it out; anything else gets the whole document.
     */
    @Test
    public void onlyAReadableVersionBelowTheSectionsReleaseLeavesItOut() {
        ConfigReconciler reconciler = withConnectivity();
        String full = reconciler.currentDocument(device(), OLD_AGENT, "0.5.1").getRevision();
        for (String version : Arrays.asList(null, "", "  ", "unknown", "fork-0.3.0", "0.6.0", "0.6.0-rc1", "0.6.0-dev",
                "0.5.1+fork", "0.5.1-myfork", "v0.4.0", "0.4.0-rc1", "0.4", "1.0.0", "0.4.0.1", "0.10.0")) {
            DesiredConfig doc = reconciler.currentDocument(device(), OLD_AGENT, version);
            assertNotNull("\"" + version + "\" gets connectivity", doc.getConnectivity());
            assertEquals(version, full, doc.getRevision());
        }
        for (String version : Arrays.asList("0.3.8", "0.3.8-rc1", "0.3.8+fork", "v0.3.0", "0.3", " 0.3.8 ")) {
            assertNull("\"" + version + "\" doesn't", reconciler.currentDocument(device(), OLD_AGENT, version).getConnectivity());
        }
    }

    /** An agent from 0.6.0 lists the sections it applies: it gets those, whatever version it reports. */
    @Test
    public void anAgentThatListsItsSectionsGetsThoseOnly() {
        ConfigReconciler reconciler = withConnectivity();
        Set<String> without = new HashSet<String>(Arrays.asList(DesiredConfigBuilder.CAPABILITY,
                "config.policies", "config.kiosk", "config.location"));
        DesiredConfig doc = reconciler.currentDocument(device(), without, "0.6.0");
        assertNull(doc.getConnectivity());
        assertNotNull(doc.getPolicies());
        assertNotNull(doc.getLocation());
        Set<String> with = new HashSet<String>(without);
        with.add("config.connectivity");
        assertNotNull(reconciler.currentDocument(device(), with, "0.3.8").getConnectivity());
    }

    /** A section the configuration leaves unset asks nothing: an agent that can't apply it is still in sync. */
    @Test
    public void aSectionTheConfigurationLeavesUnsetKeepsAnOlderAgentInSync() {
        Configuration configuration = new Configuration();
        configuration.setId(7);
        configuration.setCustomerId(1);
        ConfigReconciler reconciler = new ConfigReconciler(
                new FakeUnsecureDAO(configuration, Collections.<Application>emptyList(), null), null, Fakes.noExtensionPoints());
        DesiredConfig sent = reconciler.currentDocument(device(), OLD_AGENT, "0.3.8");
        assertEquals(reconciler.currentDocument(device(), NEW_AGENT, "0.5.1").getRevision(), sent.getRevision());
        assertEquals(sent.getRevision(), reconciler.currentRevision(device(), OLD_AGENT, "0.3.8"));
    }
}
