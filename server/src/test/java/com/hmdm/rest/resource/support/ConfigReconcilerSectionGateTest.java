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

/**
 * A device is sent every section but one whose capability its agent lacks, and needs an agent update for each section
 * its configuration sets that its agent doesn't apply.
 */
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

    /** A kiosk configuration with a camera block that also sets connectivity (since 0.4.0). */
    private static ConfigReconciler kioskWithConnectivity() {
        Configuration configuration = new Configuration();
        configuration.setId(7);
        configuration.setCustomerId(1);
        configuration.setKioskMode(true);
        configuration.setCamera(false);
        configuration.setConnectivityMode(DesiredConfigBuilder.CONNECTIVITY_ALWAYS_ON);
        return new ConfigReconciler(new FakeUnsecureDAO(configuration, Collections.<Application>emptyList(), null),
                null, Fakes.noExtensionPoints());
    }

    private static List<String> notApplied(ConfigReconciler reconciler, String agentVersion) {
        return ConfigReconciler.notApplied(reconciler.statusDocument(device()), OLD_AGENT, agentVersion);
    }

    /**
     * An agent before 0.4.0 ignores connectivity. It is still sent it, so its revision is the configuration's (in sync),
     * and it needs an agent update for connectivity.
     */
    @Test
    public void anAgentBeforeASectionsReleaseIsSentItAndIsNotInSync() {
        ConfigReconciler reconciler = kioskWithConnectivity();
        DesiredConfig sent = reconciler.currentDocument(device(), OLD_AGENT);
        assertNotNull(sent.getConnectivity());
        assertEquals(sent.getRevision(), reconciler.currentRevision(device(), OLD_AGENT));
        assertEquals(Collections.singletonList("connectivity"), notApplied(reconciler, "0.3.8"));
        assertEquals(Collections.<String>emptyList(), notApplied(reconciler, "0.4.0"));
    }

    /**
     * The fleet's version strings. Only one that reads as a version, from 0.3.0, below a section's release counts as not
     * applying it; anything else counts as applying every section. Whatever the version, nothing is left out of what is
     * sent.
     */
    @Test
    public void onlyAReadableVersionBelowTheSectionsReleaseNeedsAnUpdate() {
        ConfigReconciler reconciler = kioskWithConnectivity();
        // Below 0.3.0 (no config.apply agent is): a build without a release version, such as a development build's
        // 0.1.15 or 0.0.0-dev.
        for (String version : Arrays.asList(null, "", "  ", "unknown", "fork-0.3.0", "0.6.0", "0.6.0-rc1", "0.6.0-dev",
                "0.5.1+fork", "0.5.1-myfork", "v0.4.0", "0.4.0-rc1", "0.4", "1.0.0", "0.4.0.1", "0.10.0",
                "0.1.15", "0.0.0-dev", "0.2.9", "0.0.1")) {
            assertEquals("\"" + version + "\"", Collections.<String>emptyList(), notApplied(reconciler, version));
        }
        for (String version : Arrays.asList("0.3.8", "0.3.8-rc1", "0.3.8+fork", "v0.3.0", "0.3", " 0.3.8 ")) {
            assertEquals("\"" + version + "\"", Collections.singletonList("connectivity"), notApplied(reconciler, version));
        }
    }

    /**
     * Whatever version a device reports, it gets its kiosk, policies and location under the configuration's revision:
     * what is sent takes no version at all ({@code currentDocument(device, tokens)}), only the agent update verdict
     * does.
     */
    @Test
    public void noReportedVersionLeavesASectionOut() {
        ConfigReconciler reconciler = kioskWithConnectivity();
        DesiredConfig full = reconciler.statusDocument(device());
        DesiredConfig sent = reconciler.currentDocument(device(), OLD_AGENT);
        assertEquals(full.getRevision(), sent.getRevision());
        assertNotNull(sent.getKiosk());
        assertEquals(Boolean.FALSE, sent.getPolicies().get("camera"));
        assertNotNull(sent.getLocation());
        assertNotNull(sent.getConnectivity());
        assertEquals("nor flagged: no config.apply agent is that old", Collections.<String>emptyList(),
                notApplied(reconciler, "0.0.1"));
    }

    /** An agent from 0.6.0 lists the sections it applies: a set section it doesn't list needs an update, whatever its version. */
    @Test
    public void anAgentThatListsItsSectionsAppliesThoseOnly() {
        ConfigReconciler reconciler = kioskWithConnectivity();
        Set<String> listed = new HashSet<String>(Arrays.asList(DesiredConfigBuilder.CAPABILITY,
                "config.policies", "config.kiosk", "config.location"));
        DesiredConfig doc = reconciler.statusDocument(device());
        assertNotNull(reconciler.currentDocument(device(), listed).getConnectivity());
        assertEquals(Collections.singletonList("connectivity"), ConfigReconciler.notApplied(doc, listed, "0.6.0"));
        listed.add("config.connectivity");
        assertEquals(Collections.<String>emptyList(), ConfigReconciler.notApplied(doc, listed, "0.3.8"));
    }

    /** A section the configuration leaves unset asks nothing: an agent that can't apply it needs no update. */
    @Test
    public void aSectionTheConfigurationLeavesUnsetNeedsNoUpdate() {
        Configuration configuration = new Configuration();
        configuration.setId(7);
        configuration.setCustomerId(1);
        ConfigReconciler reconciler = new ConfigReconciler(
                new FakeUnsecureDAO(configuration, Collections.<Application>emptyList(), null), null, Fakes.noExtensionPoints());
        assertEquals(Collections.<String>emptyList(), notApplied(reconciler, "0.3.8"));
    }

    private static final ManagedAppPermission CAMERA_DENY = new ManagedAppPermission();
    static {
        CAMERA_DENY.setApplicationId(4); CAMERA_DENY.setPermission("android.permission.CAMERA"); CAMERA_DENY.setState("deny");
    }

    /**
     * An agent that lists appPermissions but lacks the capability (not Device Owner): it isn't sent the rules, and it
     * doesn't need an agent update for them either: a newer agent wouldn't apply them without Device Owner.
     */
    @Test
    public void aListedSectionWithoutItsCapabilityIsWithheldButNeedsNoUpdate() {
        ConfigReconciler reconciler = reconciler(Collections.singletonList(CAMERA_DENY));
        Set<String> listed = new HashSet<String>(Arrays.asList(DesiredConfigBuilder.CAPABILITY,
                "config.policies", "config.kiosk", "config.location", "config.connectivity", "config.appPermissions"));
        assertNull(reconciler.currentDocument(device(), listed).getAppPermissions());
        assertEquals(Collections.<String>emptyList(),
                ConfigReconciler.notApplied(reconciler.statusDocument(device()), listed, "0.6.0"));
    }

    /** An agent with the capability whose list leaves appPermissions out: still sent the rules (never released), and named. */
    @Test
    public void anUnlistedSectionIsStillSentWithItsCapability() {
        ConfigReconciler reconciler = reconciler(Collections.singletonList(CAMERA_DENY));
        Set<String> listed = new HashSet<String>(Arrays.asList(DesiredConfigBuilder.CAPABILITY,
                DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY, "config.policies", "config.kiosk", "config.location"));
        assertEquals(1, reconciler.currentDocument(device(), listed).getAppPermissions().size());
        assertEquals(Collections.singletonList("appPermissions"),
                ConfigReconciler.notApplied(reconciler.statusDocument(device()), listed, "0.6.0"));
    }

    /**
     * App permission rules an agent can't apply are left out of what it is sent (as before). They need an agent update
     * only on an agent before 0.5.0. A 0.5.0 or later agent without the capability isn't Device Owner, which no update
     * fixes, and neither does a version that doesn't read.
     */
    @Test
    public void permissionRulesNeedAnUpdateOnlyOnAnAgentThatPredatesThem() {
        ConfigReconciler reconciler = reconciler(Collections.singletonList(CAMERA_DENY));
        DesiredConfig doc = reconciler.statusDocument(device());
        assertNull(reconciler.currentDocument(device(), OLD_AGENT).getAppPermissions());
        assertEquals(Collections.singletonList("appPermissions"), ConfigReconciler.notApplied(doc, OLD_AGENT, "0.4.2"));
        for (String current : Arrays.asList("0.5.0", "0.5.1", "0.6.0-rc1", "0.5.1+fork", null, "unknown", "0.1.15")) {
            assertEquals(String.valueOf(current), Collections.<String>emptyList(),
                    ConfigReconciler.notApplied(doc, OLD_AGENT, current));
        }
        assertEquals(Collections.<String>emptyList(), ConfigReconciler.notApplied(doc, NEW_AGENT, "0.4.2"));
        assertEquals("no rules: nothing to apply", Collections.<String>emptyList(), ConfigReconciler.notApplied(
                reconciler(Collections.<ManagedAppPermission>emptyList()).statusDocument(device()), OLD_AGENT, "0.4.2"));
    }

}
