package com.hmdm.rest.resource;

import com.google.inject.Guice;
import com.hmdm.event.Event;
import com.hmdm.event.EventService;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.DeviceCreateOptions;
import com.hmdm.rest.json.DeviceInfo;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.SyncApplicationSetting;
import com.hmdm.rest.json.SyncResponse;
import com.hmdm.testsupport.LegacyDeviceWorld;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.hmdm.testsupport.LegacyDeviceWorld.AGENT_CONFIG;
import static com.hmdm.testsupport.LegacyDeviceWorld.AGENT_QR_KEY;
import static com.hmdm.testsupport.LegacyDeviceWorld.LAUNCHER_CONFIG;
import static com.hmdm.testsupport.LegacyDeviceWorld.LAUNCHER_QR_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Headwind launcher's sync routes never reach an agent-enrolled device, however the launcher names it (number,
 * old number, IMEI, serial), and never serve or create into a configuration an agent device uses; launcher devices in
 * a launcher configuration work as before.
 */
public class SyncResourceLegacyAccessTest {

    private static final String NOT_FOUND = "error.notfound.device";
    private static final String AGENT_CONFIGURATION = "error.configuration.agent.managed";

    private LegacyDeviceWorld world;
    private SyncResource resource;
    private final List<Event> events = new ArrayList<>();

    @Before
    public void setUp() {
        world = new LegacyDeviceWorld();
        world.agent("agent-1");
        world.launcher("launcher-1", LAUNCHER_CONFIG);
        EventService eventService = new EventService(null) {
            @Override public void fireEvent(Event event) {
                events.add(event);
            }
        };
        CustomerDAO customers = new CustomerDAO(null, null, null, null, null, null, null, "files", 2, null) {
            @Override public Customer findById(int customerId) {
                Customer c = new Customer();
                c.setId(customerId);
                c.setFilesDir("files");
                return c;
            }
        };
        resource = new SyncResource(world.dao, eventService, Guice.createInjector(), customers, null,
                "https://mdm.example.test", false, "secret", false, "", "", "", "X-Forwarded-For");
    }

    private static HttpServletRequest request() {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getRemoteAddr": return "203.0.113.9";
                        default: return null;
                    }
                });
    }

    private static HttpServletResponse response() {
        return (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (p, m, a) -> null);
    }

    private Response get(String number) {
        return resource.getDeviceSetting(number, request(), response());
    }

    private Response post(String number, String qrKey) {
        DeviceCreateOptions options = new DeviceCreateOptions();
        options.setConfiguration(qrKey);
        return resource.enrollDevice(options, number, request(), response());
    }

    private Response info(String number) {
        DeviceInfo info = new DeviceInfo();
        info.setDeviceId(number);
        info.setBatteryLevel(5);
        return resource.updateDeviceInfo(info, request(), response());
    }

    private Response appSettings(String number) {
        SyncApplicationSetting s = new SyncApplicationSetting();
        s.setPackageId("com.example");
        s.setName("token");
        s.setValue("x");
        return resource.saveApplicationSettings(number, Collections.singletonList(s));
    }

    private void assertNotFoundAndNothingWritten(Response r) {
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals(NOT_FOUND, r.getMessage());
        assertNothingWritten();
    }

    private void assertNothingWritten() {
        assertEquals(Collections.emptyList(), world.allWrites());
        assertTrue(events.toString(), events.isEmpty());
    }

    // ---- configuration (GET and POST): every way the launcher can name a device ----

    @Test
    public void configurationRefusesAnAgentDeviceByNumber() {
        assertNotFoundAndNothingWritten(get("agent-1"));
        assertNotFoundAndNothingWritten(post("agent-1", null));
    }

    @Test
    public void configurationRefusesAnAgentDeviceByOldNumber() {
        assertNotFoundAndNothingWritten(get("agent-1-old"));
        assertNotFoundAndNothingWritten(post("agent-1-old", null));
    }

    @Test
    public void configurationRefusesAnAgentDeviceByImei() {
        assertNotFoundAndNothingWritten(get("agent-1-imei"));
        assertNotFoundAndNothingWritten(post("agent-1-imei", LAUNCHER_QR_KEY));
    }

    @Test
    public void configurationRefusesAnAgentDeviceBySerial() {
        assertNotFoundAndNothingWritten(get("agent-1-serial"));
        assertNotFoundAndNothingWritten(post("agent-1-serial", LAUNCHER_QR_KEY));
    }

    @Test
    public void configurationServesALauncherDeviceWhoseConfigurationHasNoPassword() {
        Response r = get("launcher-1");
        assertEquals(r.getMessage(), Response.ResponseStatus.OK, r.getStatus());
        assertNull("no kiosk exit PIN set: none sent", ((SyncResponse) r.getData()).getPassword());
    }

    @Test
    public void configurationRefusesALauncherDeviceInAConfigurationAnAgentDeviceUses() {
        Device moved = world.launcher("launcher-2", AGENT_CONFIG);
        moved.setOldNumber("launcher-2-old");
        Response r = get("launcher-2");
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals(AGENT_CONFIGURATION, r.getMessage());
        assertNothingWritten();
    }

    @Test
    public void configurationCreatesALauncherDeviceInALauncherConfiguration() {
        assertEquals(Response.ResponseStatus.OK, get("new-1").getStatus());
        assertEquals(LAUNCHER_CONFIG, (int) world.byNumber.get("new-1").getConfigurationId());
        assertEquals(Response.ResponseStatus.OK, post("new-2", LAUNCHER_QR_KEY).getStatus());
        assertEquals(LAUNCHER_CONFIG, (int) world.byNumber.get("new-2").getConfigurationId());
    }

    @Test
    public void configurationNeverCreatesIntoTheDefaultConfigurationWhenAnAgentDeviceUsesIt() {
        world.settings.setNewDeviceConfigurationId(AGENT_CONFIG);
        for (Response r : new Response[]{get("new-1"), post("new-2", null)}) {
            assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
            assertEquals(AGENT_CONFIGURATION, r.getMessage());
        }
        assertNothingWritten();
    }

    @Test
    public void configurationNeverCreatesIntoAQrKeyConfigurationAnAgentDeviceUses() {
        Response r = post("new-1", AGENT_QR_KEY);
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals(AGENT_CONFIGURATION, r.getMessage());
        assertNothingWritten();
    }

    // ---- info ----

    @Test
    public void infoRefusesAnAgentDeviceByNumberAndOldNumber() {
        assertNotFoundAndNothingWritten(info("agent-1"));
        assertNotFoundAndNothingWritten(info("agent-1-old"));
    }

    @Test
    public void infoNeverCreatesIntoAConfigurationAnAgentDeviceUses() {
        world.settings.setNewDeviceConfigurationId(AGENT_CONFIG);
        Response r = info("new-1");
        assertEquals(AGENT_CONFIGURATION, r.getMessage());
        assertNothingWritten();
    }

    @Test
    public void infoStillUpdatesALauncherDevice() {
        assertEquals(Response.ResponseStatus.OK, info("launcher-1").getStatus());
        assertEquals(1, world.devices.callsTo("updateDeviceInfo").size());
        assertEquals(2, events.size());
    }

    // ---- application settings ----

    @Test
    public void applicationSettingsRefusesAnAgentDevice() {
        assertNotFoundAndNothingWritten(appSettings("agent-1"));
    }

    @Test
    public void applicationSettingsStillSavesForALauncherDevice() {
        assertEquals(Response.ResponseStatus.OK, appSettings("launcher-1").getStatus());
        assertEquals(Collections.singletonList("applicationSettings launcher-1"), world.writes);
    }
}
