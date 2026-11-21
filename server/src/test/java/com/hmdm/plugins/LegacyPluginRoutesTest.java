package com.hmdm.plugins;

import com.hmdm.event.Event;
import com.hmdm.event.EventService;
import com.hmdm.notification.persistence.NotificationDAO;
import com.hmdm.notification.persistence.mapper.NotificationMapper;
import com.hmdm.notification.rest.NotificationResource;
import com.hmdm.plugins.deviceinfo.persistence.DeviceInfoDAO;
import com.hmdm.plugins.deviceinfo.persistence.DeviceInfoSettingsDAO;
import com.hmdm.plugins.deviceinfo.persistence.domain.DeviceDynamicInfo;
import com.hmdm.plugins.deviceinfo.persistence.domain.DeviceInfoPluginSettings;
import com.hmdm.plugins.deviceinfo.rest.DeviceInfoPluginSettingsResource;
import com.hmdm.plugins.deviceinfo.rest.DeviceInfoResource;
import com.hmdm.plugins.devicelog.persistence.DeviceLogDAO;
import com.hmdm.plugins.devicelog.rest.json.UploadedDeviceLogRecord;
import com.hmdm.plugins.devicelog.rest.resource.DeviceLogResource;
import com.hmdm.plugins.messaging.persistence.MessagingDAO;
import com.hmdm.plugins.messaging.persistence.domain.Message;
import com.hmdm.plugins.messaging.persistence.mapper.MessageMapper;
import com.hmdm.plugins.messaging.rest.MessagingResource;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.FakeMapper;
import com.hmdm.testsupport.LegacyDeviceWorld;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.hmdm.testsupport.LegacyDeviceWorld.AGENT_CONFIG;
import static com.hmdm.testsupport.LegacyDeviceWorld.LAUNCHER_CONFIG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Headwind launcher's plugin and notification routes never reach an agent-enrolled device (they answer as if it
 * did not exist and read or write nothing of it), nor a launcher device in a configuration an agent device uses;
 * launcher devices work as before.
 */
public class LegacyPluginRoutesTest {

    private static final String NOT_FOUND = "error.notfound.device";
    private static final String AGENT_CONFIGURATION = "error.configuration.agent.managed";

    private LegacyDeviceWorld world;
    /** What the plugins' own DAOs were asked to do. */
    private final List<String> pluginCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<Event> events = new ArrayList<>();

    @Before
    public void setUp() {
        world = new LegacyDeviceWorld();
        world.agent("agent-1");
        world.launcher("launcher-1", LAUNCHER_CONFIG);
        world.launcher("launcher-in-agent-config", AGENT_CONFIG);
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static void assertRefused(String message, Response r) {
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals(message, r.getMessage());
    }

    private void assertNothingTouched() {
        assertEquals(Collections.emptyList(), pluginCalls);
        assertEquals(Collections.emptyList(), world.allWrites());
        assertTrue(events.toString(), events.isEmpty());
    }

    // ---- deviceinfo: /public/{number} and -plugin-settings/device/{number} ----

    private DeviceInfoResource deviceInfo() {
        DeviceInfoDAO dao = new DeviceInfoDAO(null, null, null, null, null) {
            @Override public void saveDeviceDynamicData(List<DeviceDynamicInfo> data) {
                pluginCalls.add("saveDeviceDynamicData");
            }
        };
        EventService eventService = new EventService(null) {
            @Override public void fireEvent(Event event) {
                events.add(event);
            }
        };
        return new DeviceInfoResource(dao, world.dao, null, null, Fakes.pluginStatus((p, c) -> false), eventService);
    }

    private Response saveDeviceInfo(String number) {
        return deviceInfo().saveDeviceInfo(number, new ArrayList<>(Collections.singletonList(new DeviceDynamicInfo())));
    }

    @Test
    public void deviceInfoUploadRefusesAgentDevicesAndAgentConfigurations() {
        assertRefused(NOT_FOUND, saveDeviceInfo("agent-1"));
        assertRefused(AGENT_CONFIGURATION, saveDeviceInfo("launcher-in-agent-config"));
        assertNothingTouched();
    }

    @Test
    public void deviceInfoUploadStillSavesForALauncherDevice() {
        assertEquals(Response.ResponseStatus.OK, saveDeviceInfo("launcher-1").getStatus());
        assertEquals(Collections.singletonList("saveDeviceDynamicData"), pluginCalls);
    }

    private Response deviceInfoSettings(String number) {
        DeviceInfoSettingsDAO dao = new DeviceInfoSettingsDAO(null) {
            @Override public DeviceInfoPluginSettings getPluginSettings(int customerId) {
                pluginCalls.add("getPluginSettings " + customerId);
                return new DeviceInfoPluginSettings();
            }
        };
        return new DeviceInfoPluginSettingsResource(dao, world.dao, Fakes.pluginStatus((p, c) -> false), null, null)
                .lookupDevices(number);
    }

    @Test
    public void deviceInfoSettingsRefusesAgentDevicesAndAgentConfigurations() {
        assertRefused(NOT_FOUND, deviceInfoSettings("agent-1"));
        assertRefused(AGENT_CONFIGURATION, deviceInfoSettings("launcher-in-agent-config"));
        assertNothingTouched();
    }

    @Test
    public void deviceInfoSettingsStillAnswersALauncherDevice() {
        assertEquals(Response.ResponseStatus.OK, deviceInfoSettings("launcher-1").getStatus());
        assertEquals(Collections.singletonList("getPluginSettings 1"), pluginCalls);
    }

    // ---- devicelog: /log/list/{number} and /log/rules/{number} ----

    private final CountDownLatch logsInserted = new CountDownLatch(1);

    private DeviceLogResource deviceLog() {
        FakeMapper<DeviceLogDAO> dao = new FakeMapper<>(DeviceLogDAO.class)
                .answer("insertDeviceLogRecords", a -> {
                    pluginCalls.add("insertDeviceLogRecords " + a[0]);
                    logsInserted.countDown();
                    return 1;
                })
                .answer("getDeviceLogRules", a -> {
                    pluginCalls.add("getDeviceLogRules " + a[0]);
                    return new ArrayList<>();
                });
        return new DeviceLogResource(dao.mapper, Fakes.pluginStatus((p, c) -> false), world.dao);
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

    private Response uploadLogs(String number) {
        return deviceLog().uploadLogs(number, Collections.singletonList(new UploadedDeviceLogRecord()),
                request());
    }

    @Test
    public void deviceLogRoutesRefuseAgentDevicesAndAgentConfigurations() {
        assertRefused(NOT_FOUND, uploadLogs("agent-1"));
        assertRefused(NOT_FOUND, deviceLog().getDeviceLogRules("agent-1"));
        assertRefused(AGENT_CONFIGURATION, uploadLogs("launcher-in-agent-config"));
        assertRefused(AGENT_CONFIGURATION, deviceLog().getDeviceLogRules("launcher-in-agent-config"));
        assertNothingTouched();
    }

    @Test
    public void deviceLogRoutesStillServeALauncherDevice() throws Exception {
        assertEquals(Response.ResponseStatus.OK, deviceLog().getDeviceLogRules("launcher-1").getStatus());
        assertEquals(Response.ResponseStatus.OK, uploadLogs("launcher-1").getStatus());
        assertTrue("the upload is stored", logsInserted.await(10, TimeUnit.SECONDS));
        assertEquals(new ArrayList<>(java.util.Arrays.asList("getDeviceLogRules launcher-1",
                "insertDeviceLogRecords launcher-1")), pluginCalls);
    }

    // ---- messaging: /public/status/{id}/{status} ----

    private Response messageStatus(int messageId, Map<Integer, Integer> messageDevices, FakeMapper<MessageMapper> m) {
        m.answer("getMessageDeviceId", a -> messageDevices.get((Integer) a[0]));
        return new MessagingResource(new MessagingDAO(m.mapper), world.dao, null, null, null)
                .setMessageStatus(messageId, Message.STATUS_READ);
    }

    @Test
    public void messageStatusLeavesMessagesOfAgentDevicesAndAgentConfigurationsAlone() {
        Map<Integer, Integer> messageDevices = new HashMap<>();
        messageDevices.put(1, world.byNumber.get("agent-1").getId());
        messageDevices.put(2, world.byNumber.get("launcher-in-agent-config").getId());
        FakeMapper<MessageMapper> messages = new FakeMapper<>(MessageMapper.class);
        assertRefused(NOT_FOUND, messageStatus(1, messageDevices, messages));
        assertRefused(AGENT_CONFIGURATION, messageStatus(2, messageDevices, messages));
        assertRefused(NOT_FOUND, messageStatus(3, messageDevices, messages));
        assertEquals(Collections.emptyList(), messages.callsTo("updateMessageStatus"));
    }

    @Test
    public void messageStatusStillMarksALauncherDevicesMessage() {
        Map<Integer, Integer> messageDevices = new HashMap<>();
        messageDevices.put(1, world.byNumber.get("launcher-1").getId());
        FakeMapper<MessageMapper> messages = new FakeMapper<>(MessageMapper.class);
        assertEquals(Response.ResponseStatus.OK, messageStatus(1, messageDevices, messages).getStatus());
        assertEquals(1, messages.callsTo("updateMessageStatus").size());
    }

    // ---- notifications: /notifications/device/{number} (long polling: notification module's LongPollingServletLegacyAccessTest) ----

    private FakeMapper<NotificationMapper> notifications() {
        return new FakeMapper<>(NotificationMapper.class);
    }

    @Test
    public void notificationsRefuseAgentDevicesByNumberAndOldNumber() {
        FakeMapper<NotificationMapper> n = notifications();
        NotificationResource resource = new NotificationResource(world.dao, new NotificationDAO(n.mapper));
        assertRefused(NOT_FOUND, resource.getPushMessages("agent-1"));
        assertRefused(NOT_FOUND, resource.getPushMessages("agent-1-old"));
        assertRefused(AGENT_CONFIGURATION, resource.getPushMessages("launcher-in-agent-config"));
        assertEquals("no message read or marked delivered", Collections.emptyList(), n.calls);
    }

    @Test
    public void notificationsStillDeliverToALauncherDevice() {
        FakeMapper<NotificationMapper> n = notifications();
        NotificationResource resource = new NotificationResource(world.dao, new NotificationDAO(n.mapper));
        assertEquals(Response.ResponseStatus.OK, resource.getPushMessages("launcher-1").getStatus());
        assertEquals(1, n.callsTo("getPendingMessagesByNumber").size());
    }
}
