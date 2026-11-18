/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.google.inject.Guice;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationSetting;
import com.hmdm.persistence.domain.ApplicationSettingType;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.DesiredConfigBuilder;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.BadRequestException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.hmdm.rest.resource.ConfigurationResourceAppSettingsTest.setting;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * POST /private/devices/{id}/applicationSettings: a device's overrides of its configuration's managed app
 * configuration. Needs edit_device_app_settings and access to the device, is checked like a configuration's, keeps only the apps
 * the device's configuration installs, and wakes the device.
 */
public class DeviceResourceAppSettingsTest {

    private static final int DEVICE_ID = 31;
    private static final int CONFIGURATION_ID = 7;
    private static final int INSTALLED = 4;
    private static final int NOT_INSTALLED = 5;

    private final List<String> calls = new ArrayList<>();
    private List<ApplicationSetting> saved;
    private boolean accessible = true;
    private boolean otherCustomer = false;
    private Integer configurationId = CONFIGURATION_ID;

    private DeviceResource resource() {
        DeviceDAO devices = new DeviceDAO(null, null, Guice.createInjector(), null, 3) {
            @Override public Device getDeviceById(Integer id) {
                if (otherCustomer) throw SecurityException.onCustomerDataAccessViolation(id, "device");
                if (id != DEVICE_ID) return null;
                Device d = new Device();
                d.setId(DEVICE_ID);
                d.setNumber("tab-31");
                d.setCustomerId(1);
                d.setConfigurationId(configurationId);
                return d;
            }
            @Override public boolean isAccessibleToUser(Device device) { return accessible; }
            @Override public void saveDeviceApplicationSettings(Integer id, List<ApplicationSetting> settings) {
                calls.add("save " + id);
                saved = settings;
            }
            @Override public List<ApplicationSetting> getDeviceApplicationSettings(int id) { return saved; }
        };
        UnsecureDAO unsecure = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, "files", 2, "launcher") {
            @Override public List<Application> getPlainConfigurationAppsOptimized(Integer id) {
                assertEquals(Integer.valueOf(CONFIGURATION_ID), id);
                return Arrays.asList(app(INSTALLED, DesiredConfigBuilder.ACTION_INSTALL), app(NOT_INSTALLED, 0));
            }
        };
        AgentWakeHub wakeHub = new AgentWakeHub(null) {
            @Override public void wake(String number, String kind) { calls.add("wake " + number + " " + kind); }
        };
        return new DeviceResource(devices, null, null, null, null, unsecure, wakeHub);
    }

    private static Application app(int id, int action) {
        Application a = new Application();
        a.setId(id);
        a.setAction(action);
        return a;
    }

    private static List<ApplicationSetting> body(ApplicationSetting... settings) {
        return new ArrayList<>(Arrays.asList(settings));
    }

    private static void signIn(String... permissions) {
        SecurityContext.init(Fakes.user(9, 1, permissions));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void savesTheSettingsOfInstalledAppsAndWakesTheDevice() {
        signIn("devices", "edit_device_app_settings");
        Response r = resource().saveDeviceApplicationSettings(DEVICE_ID, body(
                setting(INSTALLED, "serverUrl", ApplicationSettingType.STRING, "https://device.example"),
                setting(INSTALLED, "kioskMode", ApplicationSettingType.BOOLEAN, "true"),
                setting(NOT_INSTALLED, "serverUrl", ApplicationSettingType.STRING, "dropped")));

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(Arrays.asList("save " + DEVICE_ID, "wake tab-31 commands"), calls);
        assertEquals(2, saved.size());
        for (ApplicationSetting s : saved) assertEquals(Integer.valueOf(INSTALLED), s.getApplicationId());
        assertEquals("answers with what is stored", saved, r.getData());
    }

    @Test
    public void anEmptyListClearsThem() {
        signIn("devices", "edit_device_app_settings");
        assertEquals(Response.ResponseStatus.OK, resource().saveDeviceApplicationSettings(DEVICE_ID, body()).getStatus());
        assertEquals(0, saved.size());
        assertTrue(calls.contains("wake tab-31 commands"));
    }

    @Test
    public void aDeviceWithoutAConfigurationKeepsNone() {
        signIn("devices", "edit_device_app_settings");
        configurationId = null;
        resource().saveDeviceApplicationSettings(DEVICE_ID,
                body(setting(INSTALLED, "serverUrl", ApplicationSettingType.STRING, "https://x")));
        assertEquals(0, saved.size());
    }

    @Test
    public void withoutTheAppSettingsPermissionNothingIsSavedOrRead() {
        signIn("devices", "edit_devices");
        Response r = resource().saveDeviceApplicationSettings(DEVICE_ID,
                body(setting(INSTALLED, "serverUrl", ApplicationSettingType.STRING, "https://x")));
        assertEquals(Response.PERMISSION_DENIED().getMessage(), r.getMessage());
        assertEquals(Collections.emptyList(), calls);
        assertEquals(Response.PERMISSION_DENIED().getMessage(),
                resource().getDeviceApplicationSettings(DEVICE_ID).getMessage());
    }

    @Test
    public void aDeviceTheUserCannotReachIsDenied() {
        signIn("devices", "edit_device_app_settings");
        accessible = false;
        assertEquals(Response.PERMISSION_DENIED().getMessage(), resource().saveDeviceApplicationSettings(DEVICE_ID,
                body(setting(INSTALLED, "serverUrl", ApplicationSettingType.STRING, "https://x"))).getMessage());
        accessible = true;
        otherCustomer = true;
        assertEquals(Response.PERMISSION_DENIED().getMessage(), resource().saveDeviceApplicationSettings(DEVICE_ID,
                body(setting(INSTALLED, "serverUrl", ApplicationSettingType.STRING, "https://x"))).getMessage());
        otherCustomer = false;
        assertEquals("no such device", Response.PERMISSION_DENIED().getMessage(),
                resource().saveDeviceApplicationSettings(99, body()).getMessage());
        assertEquals(Collections.emptyList(), calls);
    }

    @Test
    public void aMalformedSettingIsABadRequestAndNothingIsSavedOrWoken() {
        signIn("devices", "edit_device_app_settings");
        for (List<ApplicationSetting> bad : Arrays.asList(
                body(setting(INSTALLED, "pollSeconds", ApplicationSettingType.INTEGER, "thirty")),
                body(setting(INSTALLED, "a", ApplicationSettingType.STRING, "x"),
                        setting(INSTALLED, "a", ApplicationSettingType.STRING, "y")),
                body(setting(NOT_INSTALLED, "kioskMode", ApplicationSettingType.BOOLEAN, "on")),
                null)) {
            try {
                resource().saveDeviceApplicationSettings(DEVICE_ID, bad);
                fail("saved " + bad);
            } catch (BadRequestException expected) {
                assertEquals(400, expected.getResponse().getStatus());
                Response entity = (Response) expected.getResponse().getEntity();
                assertEquals(Response.ResponseStatus.ERROR, entity.getStatus());
                assertFalse(entity.getMessage(), entity.getMessage().contains("thirty"));
            }
        }
        assertEquals(Collections.emptyList(), calls);
    }
}
