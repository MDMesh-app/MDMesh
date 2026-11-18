/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.plugins.audit.persistence.domain.AuditLogRecord;
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
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Application settings (an app's managed configuration) can hold secrets, such as a device's enrollment token: the
 * audit record of a configuration save or copy, and of a device settings save, keeps their names but never a value.
 */
public class AppSettingsAuditTest {

    private static final String CONFIGURATION = "{\"id\":3,\"name\":\"warehouse\",\"password\":\"482139\","
            + "\"applicationSettings\":[{\"applicationId\":4,\"name\":\"deviceToken\",\"type\":\"STRING\","
            + "\"value\":\"s3cr3t-token\"},{\"applicationId\":4,\"name\":\"pollSeconds\",\"type\":\"INTEGER\","
            + "\"value\":\"30\"}]}";
    private static final String DEVICE = "[{\"applicationId\":4,\"name\":\"deviceToken\",\"type\":\"STRING\","
            + "\"value\":\"s3cr3t-token\"},{\"applicationId\":4,\"name\":\"kioskMode\",\"type\":\"BOOLEAN\","
            + "\"value\":\"true\"}]";

    @Test
    public void auditsADeviceSettingsSave() {
        assertEquals(Optional.of(ResourceAuditInfo.UPDATE_DEVICE_APP_SETTINGS),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/devices/31/applicationSettings"));
        assertEquals(Optional.of(ResourceAuditInfo.UPDATE_DEVICE_APP_SETTINGS),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/devices/31/applicationSettings/"));
        assertFalse("reading them is not an action",
                ResourceAuditInfo.findAuditInfo("GET", "/rest/private/devices/31/applicationSettings").isPresent());
        assertFalse(ResourceAuditInfo.findAuditInfo("POST", "/rest/private/devices/31/applicationSettings/notify")
                .isPresent());
    }

    @Test
    public void aDeviceSettingsSaveKeepsTheNamesAndMasksTheValues() throws IOException, ServletException {
        AuditLogRecord record = audit("POST", "/rest/private/devices/31/applicationSettings", DEVICE);

        assertEquals("plugin.audit.action.update.device.app.settings", record.getAction());
        JsonNode stored = storedBody(record);
        assertEquals("deviceToken", stored.get(0).get("name").asText());
        assertEquals("kioskMode", stored.get(1).get("name").asText());
        assertEquals("******", stored.get(0).get("value").asText());
        assertEquals("******", stored.get(1).get("value").asText());
        assertFalse(record.toLogString(), record.toLogString().contains("s3cr3t"));
    }

    @Test
    public void aConfigurationSaveMasksTheSettingValuesAndThePin() throws IOException, ServletException {
        AuditLogRecord record = audit("PUT", "/rest/private/configurations", CONFIGURATION);

        assertEquals("plugin.audit.action.update.configuration", record.getAction());
        JsonNode stored = storedBody(record);
        assertEquals("warehouse", stored.get("name").asText());
        assertEquals("deviceToken", stored.get("applicationSettings").get(0).get("name").asText());
        assertEquals("******", stored.get("applicationSettings").get(0).get("value").asText());
        assertFalse(record.toLogString(), record.toLogString().contains("s3cr3t"));
        assertFalse(record.toLogString(), record.toLogString().contains("482139"));
    }

    @Test
    public void aConfigurationCopyMasksTheSettingValues() throws IOException, ServletException {
        AuditLogRecord record = audit("PUT", "/rest/private/configurations/copy", CONFIGURATION);

        assertEquals("plugin.audit.action.copy.configuration", record.getAction());
        assertEquals("******", storedBody(record).get("applicationSettings").get(0).get("value").asText());
        assertFalse(record.toLogString(), record.toLogString().contains("s3cr3t"));
    }

    @Test
    public void whatCannotBeReadAsSettingsIsNotStored() {
        for (String body : new String[]{"deviceToken=s3cr3t", "\"s3cr3t\"",
                "{\"applicationSettings\":{\"deviceToken\":\"s3cr3t\"}}", "[\"s3cr3t\"]", "[[\"s3cr3t\"]]"}) {
            String stored = ResourceAuditor.maskApplicationSettingValues(body);
            assertFalse(body + " -> " + stored, stored.contains("s3cr3t"));
        }
        assertEquals("{\"name\":\"warehouse\"}", ResourceAuditor.maskApplicationSettingValues("{\"name\":\"warehouse\"}"));
    }

    private static AuditLogRecord audit(String method, String uri, String body) throws IOException, ServletException {
        byte[] sent = body.getBytes(StandardCharsets.UTF_8);
        ResourceAuditInfo info = ResourceAuditInfo.findAuditInfo(method, uri).get();
        FilterChain resource = (req, res) -> {
            ((HttpServletResponse) res).setStatus(200);
            res.getOutputStream().write("{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8));
        };
        ResourceAuditor auditor = info.getResourceAuditor(request(method, uri, sent), response(), resource, "", "X-Real-IP");
        auditor.doProcess();
        AuditLogRecord record = auditor.getAuditLogRecord();
        assertEquals(Integer.valueOf(0), record.getErrorCode());
        assertTrue(record.getPayload(), record.getPayload().contains("URI: " + uri));
        return record;
    }

    private static JsonNode storedBody(AuditLogRecord record) throws IOException {
        String payload = record.getPayload();
        int start = payload.indexOf("\nBody: ") + "\nBody: ".length();
        int end = payload.indexOf("\nUser-Agent: ");
        return new ObjectMapper().readTree(payload.substring(start, end));
    }

    private static HttpServletRequest request(String method, String uri, byte[] body) {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        ServletInputStream servletInputStream = new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                AppSettingsAuditTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getMethod":
                            return method;
                        case "getRequestURI":
                            return uri;
                        case "getInputStream":
                            return servletInputStream;
                        case "getRemoteAddr":
                            return "192.0.2.10";
                        case "getLocalAddr":
                            return "192.0.2.1";
                        default:
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
                AppSettingsAuditTest.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, m, args) -> "getOutputStream".equals(m.getName()) ? servletOutputStream : null);
    }
}
