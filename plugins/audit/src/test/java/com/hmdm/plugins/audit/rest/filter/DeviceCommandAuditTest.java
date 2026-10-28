/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Optional;

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
}
