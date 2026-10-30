/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import com.hmdm.plugins.audit.persistence.domain.AuditLogRecord;
import org.json.JSONObject;
import org.junit.Test;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Device commands sent from the console get an audit log record, and the record never holds the command payload. */
public class DeviceCommandAuditTest {

    @Test
    public void auditsACommandForOneDevice() {
        assertEquals(Optional.of(ResourceAuditInfo.DEVICE_COMMAND),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/agent/v1/devices/h0001/commands"));
        assertEquals(Optional.of(ResourceAuditInfo.DEVICE_COMMAND),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/agent/v1/devices/h0001/commands/"));
    }

    @Test
    public void auditsACommandForManyDevices() {
        assertEquals(Optional.of(ResourceAuditInfo.BULK_DEVICE_COMMAND),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/agent/v1/bulk/commands"));
    }

    @Test
    public void doesNotAuditOtherRoutes() {
        // Reading the command history is not an action.
        assertFalse(ResourceAuditInfo.findAuditInfo("GET", "/rest/private/agent/v1/devices/h0001/commands").isPresent());
        assertFalse(ResourceAuditInfo.findAuditInfo("POST", "/rest/private/agent/v1/devices/commands").isPresent());
        assertFalse(ResourceAuditInfo.findAuditInfo("POST", "/rest/private/agent/v1/devices/a/b/commands").isPresent());
        assertFalse(ResourceAuditInfo.findAuditInfo("POST", "/rest/private/agent/v1/devices/h0001/commands/1").isPresent());
    }

    @Test
    public void masksThePayloadOfACommandForOneDevice() {
        String body = "{\"type\":\"device.passcodereset\",\"payload\":\"{\\\"newPassword\\\":\\\"4711\\\"}\","
                + "\"requiresCapability\":\"device.passcodereset\"}";
        String stored = ResourceAuditor.stripCommandPayload(body);
        assertFalse(stored, stored.contains("4711"));
        JSONObject json = new JSONObject(stored);
        assertEquals("device.passcodereset", json.getString("type"));
        assertEquals("device.passcodereset", json.getString("requiresCapability"));
        assertEquals("******", json.getString("payload"));
    }

    @Test
    public void masksThePayloadOfACommandForManyDevices() {
        String body = "{\"deviceIds\":[3,5],\"command\":{\"type\":\"kiosk.enter\","
                + "\"payload\":\"{\\\"password\\\":\\\"s3cret\\\"}\"}}";
        String stored = ResourceAuditor.stripCommandPayload(body);
        assertFalse(stored, stored.contains("s3cret"));
        JSONObject json = new JSONObject(stored);
        assertEquals(2, json.getJSONArray("deviceIds").length());
        assertEquals("kiosk.enter", json.getJSONObject("command").getString("type"));
        assertEquals("******", json.getJSONObject("command").getString("payload"));
    }

    @Test
    public void keepsACommandWithoutPayload() {
        JSONObject json = new JSONObject(ResourceAuditor.stripCommandPayload("{\"type\":\"device.wipe\"}"));
        assertEquals("device.wipe", json.getString("type"));
        assertFalse(json.has("payload"));
    }

    @Test
    public void doesNotStoreABodyThatIsNotJson() {
        String stored = ResourceAuditor.stripCommandPayload("type=kiosk.enter&payload=s3cret");
        assertFalse(stored, stored.contains("s3cret"));
        assertTrue(stored, stored.startsWith("("));
    }

    @Test
    public void auditRecordForOneDeviceHoldsNoSecret() throws IOException, ServletException {
        String body = "{\"type\":\"device.passcodereset\",\"payload\":\"{\\\"newPassword\\\":\\\"4711\\\"}\"}";
        AuditLogRecord record = audit("/rest/private/agent/v1/devices/h0001/commands", body);

        assertEquals("plugin.audit.action.device.command", record.getAction());
        assertTrue(record.getPayload(), record.getPayload().contains("device.passcodereset"));
        assertFalse(record.getPayload(), record.getPayload().contains("4711"));
        assertFalse(record.toLogString(), record.toLogString().contains("4711"));
    }

    @Test
    public void auditRecordForManyDevicesHoldsNoSecret() throws IOException, ServletException {
        String body = "{\"deviceIds\":[3,5],\"command\":{\"type\":\"kiosk.enter\","
                + "\"payload\":\"{\\\"password\\\":\\\"s3cret\\\"}\"}}";
        AuditLogRecord record = audit("/rest/private/agent/v1/bulk/commands", body);

        assertEquals("plugin.audit.action.device.bulk.command", record.getAction());
        assertTrue(record.getPayload(), record.getPayload().contains("kiosk.enter"));
        assertFalse(record.getPayload(), record.getPayload().contains("s3cret"));
        assertFalse(record.toLogString(), record.toLogString().contains("s3cret"));
    }

    /**
     * Runs a POST through the audit rule that matches the URI, as AuditFilter does, with a resource that checks it
     * got the body unchanged (secret included) and answers OK.
     */
    private static AuditLogRecord audit(String uri, String body) throws IOException, ServletException {
        byte[] sent = body.getBytes(StandardCharsets.UTF_8);
        ResourceAuditInfo info = ResourceAuditInfo.findAuditInfo("POST", uri).get();
        FilterChain resource = (req, res) -> {
            assertArrayEquals(sent, readAll(req.getInputStream()));
            ((HttpServletResponse) res).setStatus(200);
            res.getOutputStream().write("{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8));
        };
        ResourceAuditor auditor = info.getResourceAuditor(request(uri, sent), response(), resource, "", "X-Real-IP");
        auditor.doProcess();
        AuditLogRecord record = auditor.getAuditLogRecord();
        assertEquals(Integer.valueOf(0), record.getErrorCode());
        return record;
    }

    private static HttpServletRequest request(String uri, byte[] body) {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        ServletInputStream servletInputStream = new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                DeviceCommandAuditTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMethod":
                            return "POST";
                        case "getRequestURI":
                            return uri;
                        case "getInputStream":
                            return servletInputStream;
                        case "getRemoteAddr":
                            return "192.0.2.10";
                        case "getLocalAddr":
                            return "192.0.2.1";
                        default:
                            // No session (no signed-in user), no headers, no character encoding
                            return null;
                    }
                });
    }

    private static HttpServletResponse response() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ServletOutputStream servletOutputStream = new ServletOutputStream() {
            @Override
            public void write(int b) {
                out.write(b);
            }
        };
        return (HttpServletResponse) Proxy.newProxyInstance(
                DeviceCommandAuditTest.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> "getOutputStream".equals(method.getName()) ? servletOutputStream : null);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            out.write(b);
        }
        return out.toByteArray();
    }
}
