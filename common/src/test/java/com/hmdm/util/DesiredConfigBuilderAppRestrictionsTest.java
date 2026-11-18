package com.hmdm.util;

import com.hmdm.extension.DesiredConfigContributor;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationSetting;
import com.hmdm.persistence.domain.ApplicationSettingType;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.agent.DesiredAppRestrictionValue;
import com.hmdm.rest.json.agent.DesiredAppRestrictions;
import com.hmdm.rest.json.agent.DesiredConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Managed app configuration (protocol 1.8): the configuration's settings merged with the device's, per installed app. */
public class DesiredConfigBuilderAppRestrictionsTest {

    private static final int TABLET = 4;
    private static final int SCANNER = 5;
    private static final int NOT_INSTALLED = 6;

    private static Application app(int id, String pkg, int action) {
        Application a = new Application();
        a.setId(id); a.setPkg(pkg); a.setAction(action); a.setShowIcon(true);
        return a;
    }

    private static List<Application> apps() {
        return Arrays.asList(app(TABLET, "hr.example.tablet", DesiredConfigBuilder.ACTION_INSTALL),
                app(SCANNER, "hr.example.scanner", DesiredConfigBuilder.ACTION_INSTALL),
                app(NOT_INSTALLED, "hr.example.gone", 0));
    }

    private static ApplicationSetting setting(Integer id, int appId, String name, ApplicationSettingType type, String value) {
        ApplicationSetting s = new ApplicationSetting();
        s.setId(id); s.setApplicationId(appId); s.setName(name); s.setType(type); s.setValue(value);
        return s;
    }

    private static Configuration configuration(ApplicationSetting... settings) {
        Configuration c = new Configuration();
        c.setId(7);
        c.setApplicationSettings(settings == null ? null : new ArrayList<>(Arrays.asList(settings)));
        return c;
    }

    private static DesiredConfig build(Configuration c, Device device, ApplicationSetting... deviceSettings) {
        return DesiredConfigBuilder.build(c, apps(), Collections.<DesiredConfigContributor>emptyList(), device,
                Arrays.asList(deviceSettings));
    }

    private static Map<String, DesiredAppRestrictionValue> values(DesiredConfig d, String pkg) {
        for (DesiredAppRestrictions r : d.getAppRestrictions()) if (r.getPackageName().equals(pkg)) return r.getValues();
        return null;
    }

    private static Device device() {
        Device d = new Device();
        d.setId(31); d.setNumber("tab-31"); d.setImei("356938035643809");
        return d;
    }

    @Test
    public void aConfigurationWhoseSettingsWereNotLoadedLeavesTheSectionOut() {
        DesiredConfig d = build(configuration((ApplicationSetting[]) null), device(),
                setting(1, TABLET, "serverUrl", ApplicationSettingType.STRING, "https://x"));
        assertNull(d.getAppRestrictions());
        assertFalse(DesiredConfigBuilder.canonicalJson(d).contains("appRestrictions"));
    }

    @Test
    public void noSettingsIsAnExplicitEmptySection() {
        DesiredConfig d = build(configuration(), device());
        assertEquals(0, d.getAppRestrictions().size());
        assertTrue(DesiredConfigBuilder.canonicalJson(d).contains("\"appRestrictions\":[]"));
    }

    @Test
    public void theDeviceWinsAndMayAddKeysOfItsOwn() {
        Configuration c = configuration(
                setting(1, TABLET, "serverUrl", ApplicationSettingType.STRING, "https://cfg.example"),
                setting(2, TABLET, "pollSeconds", ApplicationSettingType.INTEGER, "30"),
                setting(3, SCANNER, "beep", ApplicationSettingType.BOOLEAN, "false"));
        DesiredConfig d = build(c, device(),
                setting(10, TABLET, "serverUrl", ApplicationSettingType.STRING, "https://device.example"),
                setting(11, TABLET, "kioskMode", ApplicationSettingType.BOOLEAN, "true"));

        assertEquals("sorted by package", Arrays.asList("hr.example.scanner", "hr.example.tablet"),
                Arrays.asList(d.getAppRestrictions().get(0).getPackageName(), d.getAppRestrictions().get(1).getPackageName()));
        Map<String, DesiredAppRestrictionValue> tablet = values(d, "hr.example.tablet");
        assertEquals("keys sorted", Arrays.asList("kioskMode", "pollSeconds", "serverUrl"), new ArrayList<>(tablet.keySet()));
        assertEquals("https://device.example", tablet.get("serverUrl").getValue());
        assertEquals(Boolean.TRUE, tablet.get("kioskMode").getValue());
        assertEquals("bool", tablet.get("kioskMode").getType());
        assertEquals(Integer.valueOf(30), tablet.get("pollSeconds").getValue());
        assertEquals("int", tablet.get("pollSeconds").getType());
        assertEquals(Boolean.FALSE, values(d, "hr.example.scanner").get("beep").getValue());
        assertTrue(DesiredConfigBuilder.canonicalJson(d).contains(
                "{\"packageName\":\"hr.example.tablet\",\"values\":{\"kioskMode\":{\"type\":\"bool\",\"value\":true},"
                        + "\"pollSeconds\":{\"type\":\"int\",\"value\":30},"
                        + "\"serverUrl\":{\"type\":\"string\",\"value\":\"https://device.example\"}}}"));
    }

    @Test
    public void onlyTheAppsTheConfigurationInstallsGetValues() {
        Configuration c = configuration(
                setting(1, NOT_INSTALLED, "serverUrl", ApplicationSettingType.STRING, "https://x"),
                setting(2, 99, "serverUrl", ApplicationSettingType.STRING, "https://y"));
        DesiredConfig d = build(c, device(), setting(3, NOT_INSTALLED, "token", ApplicationSettingType.STRING, "t"));
        assertEquals(0, d.getAppRestrictions().size());
    }

    @Test
    public void aDeviceOverrideChangesTheRevision() {
        Configuration c = configuration(setting(1, TABLET, "serverUrl", ApplicationSettingType.STRING, "https://cfg"));
        String plain = build(c, device()).getRevision();
        assertEquals("deterministic", plain, build(c, device()).getRevision());
        assertNotEquals(plain, build(c, device(),
                setting(2, TABLET, "serverUrl", ApplicationSettingType.STRING, "https://dev")).getRevision());
    }

    @Test
    public void placeholdersTakeTheDevicesFieldsLiterally() {
        ApplicationSetting token = setting(1, TABLET, "token", ApplicationSettingType.STRING, "id=%NUMBER%;c=%CUSTOM1%;i=%IMEI%;x=%OTHER%");
        token.setVariable(true);
        ApplicationSetting literal = setting(2, TABLET, "raw", ApplicationSettingType.STRING, "%NUMBER%");
        Device d = device();
        // A regex replacement would read $1 as a group and \ as an escape; a placeholder in a field is not replaced again.
        d.setCustom1("pa$1\\s%IMEI%");
        Map<String, DesiredAppRestrictionValue> v = values(build(configuration(token, literal), d), "hr.example.tablet");
        assertEquals("id=tab-31;c=pa$1\\s%IMEI%;i=356938035643809;x=%OTHER%", v.get("token").getValue());
        assertEquals("not variable: sent as it is", "%NUMBER%", v.get("raw").getValue());
    }

    @Test
    public void aDevicesOwnVariableSettingTakesItsFieldsToo() {
        ApplicationSetting own = setting(1, TABLET, "who", ApplicationSettingType.STRING, "%NUMBER%/%PHONE%");
        own.setVariable(true);
        assertEquals("tab-31/", values(build(configuration(), device(), own), "hr.example.tablet").get("who").getValue());
    }

    @Test
    public void aValueThatIsNotItsTypeIsLeftOut() {
        Configuration c = configuration(
                setting(1, TABLET, "a", ApplicationSettingType.BOOLEAN, "yes"),
                setting(2, TABLET, "b", ApplicationSettingType.BOOLEAN, "TRUE"),
                setting(3, TABLET, "c", ApplicationSettingType.INTEGER, "3000000000"),
                setting(4, TABLET, "d", ApplicationSettingType.INTEGER, "+5"),
                setting(5, TABLET, "e", ApplicationSettingType.INTEGER, " 5"),
                setting(6, TABLET, "f", ApplicationSettingType.INTEGER, "٥"),
                setting(7, TABLET, "g", ApplicationSettingType.STRING, null),
                setting(8, TABLET, "h", null, "x"),
                setting(9, TABLET, "ok", ApplicationSettingType.INTEGER, "-2147483648"),
                setting(10, SCANNER, "bad", ApplicationSettingType.INTEGER, "1.5"));
        DesiredConfig d = build(c, device());
        assertEquals("a package without one good value has no entry", 1, d.getAppRestrictions().size());
        Map<String, DesiredAppRestrictionValue> v = values(d, "hr.example.tablet");
        assertEquals(Collections.singleton("ok"), v.keySet());
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), v.get("ok").getValue());
    }

    @Test
    public void ofTwoRowsWithTheSameNameTheNewerWinsWhateverTheOrder() {
        ApplicationSetting older = setting(1, TABLET, "serverUrl", ApplicationSettingType.STRING, "old");
        ApplicationSetting newer = setting(2, TABLET, "serverUrl", ApplicationSettingType.STRING, "new");
        assertEquals("new", values(build(configuration(newer, older), device()), "hr.example.tablet").get("serverUrl").getValue());
        assertEquals("new", values(build(configuration(older, newer), device()), "hr.example.tablet").get("serverUrl").getValue());
    }

    @Test
    public void theRestrictionValueOfEachType() {
        assertEquals(Boolean.TRUE, DesiredConfigBuilder.restrictionValue(ApplicationSettingType.BOOLEAN, "true").getValue());
        assertEquals(Integer.valueOf(2147483647),
                DesiredConfigBuilder.restrictionValue(ApplicationSettingType.INTEGER, "2147483647").getValue());
        assertNull(DesiredConfigBuilder.restrictionValue(ApplicationSettingType.INTEGER, "2147483648"));
        assertNull(DesiredConfigBuilder.restrictionValue(ApplicationSettingType.INTEGER, ""));
        assertEquals("", DesiredConfigBuilder.restrictionValue(ApplicationSettingType.STRING, "").getValue());
    }

    @Test
    public void aDocumentVariesByDeviceOnlyForAVariableSettingWithAPlaceholder() {
        ApplicationSetting variable = setting(1, TABLET, "t", ApplicationSettingType.STRING, "%NUMBER%");
        variable.setVariable(true);
        ApplicationSetting variableWithout = setting(2, TABLET, "u", ApplicationSettingType.STRING, "plain");
        variableWithout.setVariable(true);
        ApplicationSetting notVariable = setting(3, TABLET, "v", ApplicationSettingType.STRING, "%NUMBER%");
        assertTrue(DesiredConfigBuilder.variesByDevice(Arrays.asList(notVariable, variable)));
        assertFalse(DesiredConfigBuilder.variesByDevice(Arrays.asList(notVariable, variableWithout)));
        assertFalse(DesiredConfigBuilder.variesByDevice(null));
    }

    @Test
    public void aSettingPrintsNoValue() {
        ApplicationSetting s = setting(1, TABLET, "deviceToken", ApplicationSettingType.STRING, "s3cr3t-token");
        String text = s.toString();
        assertTrue(text, text.contains("deviceToken"));
        assertFalse(text, text.contains("s3cr3t-token"));
        assertNull(setting(2, TABLET, "n", ApplicationSettingType.STRING, null).getValueForDevice(device()));
    }
}
