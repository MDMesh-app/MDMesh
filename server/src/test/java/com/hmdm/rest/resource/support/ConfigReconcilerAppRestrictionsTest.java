package com.hmdm.rest.resource.support;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationSetting;
import com.hmdm.persistence.domain.ApplicationSettingType;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceSyncRow;
import com.hmdm.persistence.domain.ManagedAppPermission;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.DesiredConfigBuilder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Managed app configuration is a section only agents with app.managedConfig get: an older agent gets the document
 * without it, under that document's revision, and the console's sync status compares it with what it can apply.
 */
public class ConfigReconcilerAppRestrictionsTest {
    private static final int APP = 4;

    private static final class FakeUnsecureDAO extends UnsecureDAO {
        final Configuration configuration = new Configuration();
        List<ApplicationSetting> configurationSettings = new ArrayList<>();
        final Map<Integer, List<ApplicationSetting>> deviceSettings = new HashMap<>();
        final Map<String, Device> devices = new HashMap<>();
        int deviceSettingReads;
        int configurationReads;

        FakeUnsecureDAO() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    "/tmp", 0, "com.mdmesh.agent");
            configuration.setId(7);
            configuration.setCustomerId(1);
        }

        @Override public Configuration getConfigurationById(Integer id) {
            configurationReads++;
            return configuration;
        }
        @Override public List<Application> getPlainConfigurationAppsOptimized(Integer id) {
            Application app = new Application();
            app.setId(APP); app.setPkg("hr.example.tablet"); app.setAction(DesiredConfigBuilder.ACTION_INSTALL);
            return Collections.singletonList(app);
        }
        @Override public List<ManagedAppPermission> getManagedAppPermissions(Integer id) { return Collections.emptyList(); }
        @Override public List<ApplicationSetting> getConfigurationAppSettings(Integer id) { return configurationSettings; }
        @Override public List<ApplicationSetting> getDeviceAppSettings(int deviceId) {
            deviceSettingReads++;
            List<ApplicationSetting> s = deviceSettings.get(deviceId);
            return s == null ? Collections.<ApplicationSetting>emptyList() : s;
        }
        @Override public Device getDeviceByNumber(String number) { return devices.get(number); }
    }

    private final FakeUnsecureDAO dao = new FakeUnsecureDAO();
    private final ConfigReconciler reconciler = new ConfigReconciler(dao, null, Fakes.noExtensionPoints());

    private static ApplicationSetting setting(String name, String value) {
        ApplicationSetting s = new ApplicationSetting();
        s.setApplicationId(APP); s.setName(name); s.setType(ApplicationSettingType.STRING); s.setValue(value);
        return s;
    }

    private Device device(int id, String number) {
        Device d = new Device();
        d.setId(id); d.setNumber(number); d.setConfigurationId(7); d.setCustomerId(1);
        dao.devices.put(number, d);
        return d;
    }

    private static Set<String> tokens(String... t) {
        return new HashSet<>(Arrays.asList(t));
    }

    private static final Set<String> OLD_AGENT = tokens(DesiredConfigBuilder.CAPABILITY,
            DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY);
    private static final Set<String> NEW_AGENT = tokens(DesiredConfigBuilder.CAPABILITY,
            DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY, DesiredConfigBuilder.APP_RESTRICTIONS_CAPABILITY);
    private static final Set<String> OLDEST_AGENT = tokens(DesiredConfigBuilder.CAPABILITY);

    @Test
    public void aCapableAgentGetsTheDevicesMergedSectionAndAnOlderOneNone() {
        dao.configurationSettings.add(setting("serverUrl", "https://cfg.example"));
        dao.deviceSettings.put(31, Collections.singletonList(setting("serverUrl", "https://device.example")));
        Device device = device(31, "tab-31");

        DesiredConfig capable = reconciler.currentDocument(device, NEW_AGENT);
        DesiredConfig older = reconciler.currentDocument(device, OLD_AGENT);

        assertEquals("https://device.example",
                capable.getAppRestrictions().get(0).getValues().get("serverUrl").getValue());
        assertNull(older.getAppRestrictions());
        assertEquals("the older agent still gets the sections it can apply", 0, older.getAppPermissions().size());
        assertNotEquals(capable.getRevision(), older.getRevision());
        assertEquals(DesiredConfigBuilder.revision(older), older.getRevision());
    }

    /** A server update must not show every older agent out of sync: a configuration without values asks nothing new. */
    @Test
    public void anOlderAgentIsInSyncWithAConfigurationWithoutValues() {
        Device device = device(31, "tab-31");
        for (Set<String> agent : Arrays.asList(OLDEST_AGENT, OLD_AGENT, NEW_AGENT)) {
            assertEquals(reconciler.currentDocument(device, agent).getRevision(), reconciler.currentRevision(device, agent));
        }
        assertNotEquals("the capable agent gets the explicit empty section (it clears earlier values)",
                reconciler.currentDocument(device, OLD_AGENT).getRevision(),
                reconciler.currentDocument(device, NEW_AGENT).getRevision());
    }

    /** An older agent can't set the values: it applies the rest, and never shows in sync. */
    @Test
    public void anOlderAgentIsNeverInSyncWithAConfigurationWithValues() {
        dao.configurationSettings.add(setting("serverUrl", "https://cfg.example"));
        Device device = device(31, "tab-31");
        assertNotEquals(reconciler.currentDocument(device, OLD_AGENT).getRevision(),
                reconciler.currentRevision(device, OLD_AGENT));
        assertNotEquals(reconciler.currentDocument(device, OLDEST_AGENT).getRevision(),
                reconciler.currentRevision(device, OLDEST_AGENT));
        assertEquals(reconciler.currentDocument(device, NEW_AGENT).getRevision(),
                reconciler.currentRevision(device, NEW_AGENT));
    }

    @Test
    public void aDeviceOverrideAloneKeepsAnOlderAgentOutOfSync() {
        dao.deviceSettings.put(31, Collections.singletonList(setting("token", "t-31")));
        Device withOverride = device(31, "tab-31");
        Device without = device(32, "tab-32");
        assertNotEquals(reconciler.currentDocument(withOverride, OLD_AGENT).getRevision(),
                reconciler.currentRevision(withOverride, OLD_AGENT));
        assertEquals(reconciler.currentDocument(without, OLD_AGENT).getRevision(),
                reconciler.currentRevision(without, OLD_AGENT));
    }

    @Test
    public void statusRevisionLeavesTheDocumentAsItWas() {
        DesiredConfig doc = reconciler.currentDocument(device(31, "tab-31"));
        String json = DesiredConfigBuilder.canonicalJson(doc);
        ConfigReconciler.statusRevision(doc, OLDEST_AGENT);
        assertEquals(json, DesiredConfigBuilder.canonicalJson(doc));
    }

    private static DeviceSyncRow row(String number, boolean ownSettings) {
        DeviceSyncRow r = new DeviceSyncRow();
        r.setDeviceNumber(number); r.setConfigurationId(7); r.setOwnAppSettings(ownSettings);
        return r;
    }

    @Test
    public void theSyncSummaryBuildsAConfigurationsDocumentOnceExceptForADeviceWithItsOwnSettings() {
        dao.configurationSettings.add(setting("serverUrl", "https://cfg.example"));
        dao.deviceSettings.put(31, Collections.singletonList(setting("serverUrl", "https://device.example")));
        Device own = device(31, "tab-31");
        device(32, "tab-32");
        device(33, "tab-33");
        ConfigReconciler.StatusDocuments documents = reconciler.statusDocuments(1);

        DesiredConfig a = documents.of(row("tab-32", false));
        DesiredConfig b = documents.of(row("tab-33", false));
        DesiredConfig c = documents.of(row("tab-31", true));

        assertSame("shared", a, b);
        assertEquals("only the device with its own settings had them read", 1, dao.deviceSettingReads);
        assertEquals(reconciler.currentDocument(own).getRevision(), c.getRevision());
        assertNotEquals(a.getRevision(), c.getRevision());
        assertNull("a device that is gone has no document", documents.of(row("tab-99", true)));
    }

    @Test
    public void theSyncSummaryBuildsEachDocumentForAConfigurationWithDevicePlaceholders() {
        ApplicationSetting token = setting("who", "%NUMBER%");
        token.setVariable(true);
        dao.configurationSettings.add(token);
        device(32, "tab-32");
        device(33, "tab-33");
        ConfigReconciler.StatusDocuments documents = reconciler.statusDocuments(1);

        DesiredConfig a = documents.of(row("tab-32", false));
        DesiredConfig b = documents.of(row("tab-33", false));

        assertEquals("tab-32", a.getAppRestrictions().get(0).getValues().get("who").getValue());
        assertEquals("tab-33", b.getAppRestrictions().get(0).getValues().get("who").getValue());
        assertNotEquals(a.getRevision(), b.getRevision());
    }
}
