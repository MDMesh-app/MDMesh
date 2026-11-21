package com.hmdm.notification.rest;

import com.hmdm.notification.PushSenderPolling;
import com.hmdm.notification.persistence.NotificationDAO;
import com.hmdm.notification.persistence.mapper.NotificationMapper;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.rest.filter.PublicIPFilter;
import org.junit.Test;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The launcher's long poll never waits on an agent-enrolled device, nor on a launcher device in a configuration an
 * agent device uses: it answers 404 and reads no message. A launcher device's poll is held open as before.
 */
public class LongPollingServletLegacyAccessTest {

    private static final int LAUNCHER_CONFIG = 10, AGENT_CONFIG = 20, AGENT_ID = 1;

    private final Map<String, Device> devices = new HashMap<>();
    /** Every call to the notification mapper (reads mark messages delivered). */
    private final List<String> notificationCalls = new ArrayList<>();
    private final List<String> requestCalls = new ArrayList<>();

    public LongPollingServletLegacyAccessTest() {
        devices.put("agent-1", device(AGENT_ID, "agent-1", AGENT_CONFIG));
        devices.put("launcher-1", device(2, "launcher-1", LAUNCHER_CONFIG));
        devices.put("launcher-in-agent-config", device(3, "launcher-in-agent-config", AGENT_CONFIG));
    }

    private static Device device(int id, String number, int configurationId) {
        Device d = new Device();
        d.setId(id);
        d.setNumber(number);
        d.setConfigurationId(configurationId);
        return d;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            switch (m.getName()) {
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default: return answer.apply(m.getName(), a);
            }
        });
    }

    /** The poll's status: what sendError got, or 0 when it was held open. */
    private int poll(String number) throws Exception {
        DeviceMapper deviceMapper = proxy(DeviceMapper.class, (m, a) -> {
            switch (m) {
                case "getDeviceByNumber": return devices.get((String) a[0]);
                case "isAgentDevice": return a[0].equals(AGENT_ID);
                case "isAgentConfiguration": return a[0].equals(AGENT_CONFIG);
                default: throw new AssertionError("unexpected device mapper call " + m);
            }
        });
        UnsecureDAO unsecureDAO = new UnsecureDAO(deviceMapper, null, null, null, null, null, null, null, null, null,
                null, null, null, null, "files", 2, "launcher");
        NotificationDAO notifications = new NotificationDAO(proxy(NotificationMapper.class, (m, a) -> {
            notificationCalls.add(m);
            return new ArrayList<>();
        }));
        LongPollingServlet servlet = new LongPollingServlet(unsecureDAO, notifications,
                new PushSenderPolling(notifications), new PublicIPFilter(), 60, false, "secret");

        HttpServletRequest request = proxy(HttpServletRequest.class, (m, a) -> {
            requestCalls.add(m);
            switch (m) {
                case "getMethod": return "GET";
                case "getRequestURI": return LongPollingServlet.BASE_PATH + number;
                case "startAsync": return proxy(AsyncContext.class, (m2, a2) -> null);
                default: return null;
            }
        });
        int[] status = {0};
        HttpServletResponse response = proxy(HttpServletResponse.class, (m, a) -> {
            if (m.equals("sendError")) status[0] = (Integer) a[0];
            return null;
        });
        servlet.service(request, response);
        return status[0];
    }

    @Test
    public void refusesAnAgentDevice() throws Exception {
        assertEquals(404, poll("agent-1"));
        assertFalse(requestCalls.toString(), requestCalls.contains("startAsync"));
        assertTrue(notificationCalls.toString(), notificationCalls.isEmpty());
    }

    @Test
    public void refusesALauncherDeviceInAConfigurationAnAgentDeviceUses() throws Exception {
        assertEquals(404, poll("launcher-in-agent-config"));
        assertFalse(requestCalls.toString(), requestCalls.contains("startAsync"));
        assertTrue(notificationCalls.toString(), notificationCalls.isEmpty());
    }

    @Test
    public void holdsALauncherDevicesPollOpen() throws Exception {
        assertEquals(0, poll("launcher-1"));
        assertTrue(requestCalls.toString(), requestCalls.contains("startAsync"));
        assertEquals(1, notificationCalls.size());
    }
}
