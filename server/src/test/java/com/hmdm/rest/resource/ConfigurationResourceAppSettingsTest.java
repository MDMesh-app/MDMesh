package com.hmdm.rest.resource;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationSetting;
import com.hmdm.persistence.domain.ApplicationSettingType;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.DesiredConfigBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.BadRequestException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** PUT /configurations: applicationSettings are checked before anything is saved, and kept only for installed apps. */
public class ConfigurationResourceAppSettingsTest {

    @Before
    public void setUp() {
        SecurityContext.init(Fakes.user(5, 1, "configurations"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static Application app(int id, int action) {
        Application a = new Application();
        a.setId(id);
        a.setAction(action);
        return a;
    }

    static ApplicationSetting setting(Integer appId, String name, ApplicationSettingType type, String value) {
        ApplicationSetting s = new ApplicationSetting();
        s.setApplicationId(appId);
        s.setName(name);
        s.setType(type);
        s.setValue(value);
        return s;
    }

    private static Configuration withSettings(ApplicationSetting... settings) {
        Configuration c = new Configuration();
        c.setId(3);
        c.setName("warehouse");
        // 1 installed, 2 "Do not install"; 3 was taken out of the configuration (on the Applications page, say).
        c.setApplications(new ArrayList<>(Arrays.asList(app(1, DesiredConfigBuilder.ACTION_INSTALL), app(2, 0))));
        c.setApplicationSettings(settings == null ? null : new ArrayList<>(Arrays.asList(settings)));
        return c;
    }

    /** The message of the 400 the save answers with; nothing is saved (no DAO is wired). */
    private static String refused(Configuration c) {
        try {
            new ConfigurationResource().updateConfiguration(c);
            fail("saved " + c.getApplicationSettings().size() + " settings");
            return null;
        } catch (BadRequestException expected) {
            assertEquals(400, expected.getResponse().getStatus());
            Response body = (Response) expected.getResponse().getEntity();
            assertEquals(Response.ResponseStatus.ERROR, body.getStatus());
            return body.getMessage();
        }
    }

    @Test
    public void settingsForAppsTheSaveDoesNotInstallAreDroppedNotRefused() {
        Configuration c = withSettings(setting(1, "serverUrl", ApplicationSettingType.STRING, "https://a"),
                setting(2, "serverUrl", ApplicationSettingType.STRING, "https://b"),
                setting(3, "pollSeconds", ApplicationSettingType.INTEGER, "30"));
        ConfigurationResource.normalizeApplicationSettings(c);
        assertEquals(1, c.getApplicationSettings().size());
        assertEquals(Integer.valueOf(1), c.getApplicationSettings().get(0).getApplicationId());
    }

    @Test
    public void aSaveWithoutTheListKeepsWhatIsStored() {
        Configuration c = withSettings((ApplicationSetting[]) null);
        ConfigurationResource.normalizeApplicationSettings(c);
        assertNull(c.getApplicationSettings());
    }

    @Test
    public void wellFormedSettingsOfEachTypeAreKept() {
        Configuration c = withSettings(setting(1, "serverUrl", ApplicationSettingType.STRING, ""),
                setting(1, "pollSeconds", ApplicationSettingType.INTEGER, "-2147483648"),
                setting(1, "kioskMode", ApplicationSettingType.BOOLEAN, "false"),
                setting(2, "serverUrl", ApplicationSettingType.STRING, "the same name on another app"));
        ConfigurationResource.normalizeApplicationSettings(c);
        assertEquals(3, c.getApplicationSettings().size());
    }

    @Test
    public void malformedSettingsAreABadRequestThatNamesTheKeyNotTheValue() {
        String message = refused(withSettings(setting(1, "pollSeconds", ApplicationSettingType.INTEGER, "s3cr3t")));
        assertTrue(message, message.contains("pollSeconds"));
        assertFalse(message, message.contains("s3cr3t"));

        refused(withSettings(setting(1, "kioskMode", ApplicationSettingType.BOOLEAN, "yes")));
        refused(withSettings(setting(1, "kioskMode", ApplicationSettingType.BOOLEAN, "True")));
        refused(withSettings(setting(1, "pollSeconds", ApplicationSettingType.INTEGER, "2147483648")));
        refused(withSettings(setting(1, "pollSeconds", ApplicationSettingType.INTEGER, "+30")));
        refused(withSettings(setting(1, "serverUrl", null, "https://a")));
        refused(withSettings(setting(1, "serverUrl", ApplicationSettingType.STRING, null)));
        refused(withSettings(setting(1, " ", ApplicationSettingType.STRING, "a")));
        refused(withSettings(setting(1, null, ApplicationSettingType.STRING, "a")));
        refused(withSettings(setting(null, "serverUrl", ApplicationSettingType.STRING, "a")));
        refused(withSettings((ApplicationSetting) null));
        refused(withSettings(setting(1, "serverUrl", ApplicationSettingType.STRING, "a"),
                setting(1, "serverUrl", ApplicationSettingType.STRING, "b")));
        StringBuilder longName = new StringBuilder();
        while (longName.length() <= ConfigurationResource.MAX_SETTING_NAME_LENGTH) longName.append('k');
        refused(withSettings(setting(1, longName.toString(), ApplicationSettingType.STRING, "a")));
        StringBuilder longValue = new StringBuilder();
        while (longValue.length() <= ConfigurationResource.MAX_SETTING_VALUE_LENGTH) longValue.append('v');
        refused(withSettings(setting(1, "token", ApplicationSettingType.STRING, longValue.toString())));
        refused(withSettings(setting(1, "pollSeconds", ApplicationSettingType.INTEGER, "00000000030")));
        refused(withSettings(setting(1, "serverUrl", ApplicationSettingType.STRING, "a"),
                setting(1, " serverUrl ", ApplicationSettingType.STRING, "b")));
    }

    @Test
    public void malformedRowsOfAnAppTheSaveDropsAreDroppedUnchecked() {
        // Headwind-era rows of apps 2 (not installed) and 3 (gone): a value that fits no type, a duplicate, no value.
        Configuration c = withSettings(setting(1, "serverUrl", ApplicationSettingType.STRING, "https://a"),
                setting(2, "pollSeconds", ApplicationSettingType.INTEGER, ""),
                setting(3, "pollSeconds", ApplicationSettingType.INTEGER, "thirty"),
                setting(3, "pollSeconds", ApplicationSettingType.INTEGER, "thirty"),
                setting(3, "token", ApplicationSettingType.STRING, null));
        ConfigurationResource.normalizeApplicationSettings(c);
        assertEquals(1, c.getApplicationSettings().size());
        assertEquals("serverUrl", c.getApplicationSettings().get(0).getName());
    }

    @Test
    public void aNameIsStoredAndCheckedTrimmed() {
        StringBuilder longName = new StringBuilder();
        while (longName.length() < ConfigurationResource.MAX_SETTING_NAME_LENGTH) longName.append('k');
        Configuration c = withSettings(setting(1, "  serverUrl ", ApplicationSettingType.STRING, "https://a"),
                setting(1, " " + longName + " ", ApplicationSettingType.STRING, "b"));
        ConfigurationResource.normalizeApplicationSettings(c);
        assertEquals("serverUrl", c.getApplicationSettings().get(0).getName());
        assertEquals(longName.toString(), c.getApplicationSettings().get(1).getName());
    }

    @Test
    public void aNumberIsAMinusAndOneToTenDigitsWithin32Bits() {
        Configuration c = withSettings(setting(1, "a", ApplicationSettingType.INTEGER, "0000000030"),
                setting(1, "b", ApplicationSettingType.INTEGER, "-0"),
                setting(1, "c", ApplicationSettingType.INTEGER, "2147483647"));
        ConfigurationResource.normalizeApplicationSettings(c);
        assertEquals(3, c.getApplicationSettings().size());
        refused(withSettings(setting(1, "d", ApplicationSettingType.INTEGER, " 5")));
        refused(withSettings(setting(1, "e", ApplicationSettingType.INTEGER, "")));
    }

    @Test
    public void tooManySettingsForOneAppAreABadRequest() {
        List<ApplicationSetting> settings = new ArrayList<>();
        for (int i = 0; i <= ConfigurationResource.MAX_SETTINGS_PER_APP; i++) {
            settings.add(setting(1, "key" + i, ApplicationSettingType.STRING, "v"));
        }
        refused(withSettings(settings.toArray(new ApplicationSetting[0])));
        settings.remove(0);
        Configuration atTheLimit = withSettings(settings.toArray(new ApplicationSetting[0]));
        ConfigurationResource.normalizeApplicationSettings(atTheLimit);
        assertEquals(ConfigurationResource.MAX_SETTINGS_PER_APP, atTheLimit.getApplicationSettings().size());
    }

    @Test
    public void aCopyKeepsTheSettings() {
        Configuration c = withSettings(setting(1, "serverUrl", ApplicationSettingType.STRING, "https://a"));
        assertEquals("https://a", c.newCopy().getApplicationSettings().get(0).getValue());
    }
}
