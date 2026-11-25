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
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Starting, promoting and stopping an agent rollout (Settings → Agent rollout) each get an audit log record: who rolled
 * out which agent to which devices, and when.
 */
public class AgentRolloutAuditTest {

    private static final String ROLLOUT = "/rest/private/agent/v1/rollout";

    private static final String SHA256 = "4d7fec9b2323fbdcdd5c197efaa5bb45484780177372bbc5b6d61ee19fd254ed";

    @Test
    public void auditsStartingARollout() {
        assertEquals(Optional.of(ResourceAuditInfo.START_AGENT_ROLLOUT), ResourceAuditInfo.findAuditInfo("POST", ROLLOUT));
        assertEquals(Optional.of(ResourceAuditInfo.START_AGENT_ROLLOUT),
                ResourceAuditInfo.findAuditInfo("POST", ROLLOUT + "/"));
    }

    @Test
    public void auditsPromotingAndStoppingARollout() {
        assertEquals(Optional.of(ResourceAuditInfo.PROMOTE_AGENT_ROLLOUT),
                ResourceAuditInfo.findAuditInfo("POST", ROLLOUT + "/3/promote"));
        assertEquals(Optional.of(ResourceAuditInfo.CANCEL_AGENT_ROLLOUT),
                ResourceAuditInfo.findAuditInfo("POST", ROLLOUT + "/3/cancel"));
        assertEquals(Optional.of(ResourceAuditInfo.CANCEL_AGENT_ROLLOUT),
                ResourceAuditInfo.findAuditInfo("POST", ROLLOUT + "/3/cancel/"));
    }

    @Test
    public void doesNotAuditReadingARollout() {
        // The console polls these; reading is not an action.
        assertFalse(ResourceAuditInfo.findAuditInfo("GET", ROLLOUT + "/active").isPresent());
        assertFalse(ResourceAuditInfo.findAuditInfo("GET", ROLLOUT + "/summary").isPresent());
        assertFalse(ResourceAuditInfo.findAuditInfo("GET", ROLLOUT).isPresent());
        assertFalse(ResourceAuditInfo.findAuditInfo("POST", ROLLOUT + "/active").isPresent());
        assertFalse(ResourceAuditInfo.findAuditInfo("POST", ROLLOUT + "/a/b/promote").isPresent());
        assertFalse(ResourceAuditInfo.findAuditInfo("POST", ROLLOUT + "/promote").isPresent());
    }

    @Test
    public void auditRecordForStartingARolloutNamesTheAgentAndTheCanaryDevices() throws IOException, ServletException {
        String body = "{\"targetVersion\":\"0.5.1\",\"packageName\":\"com.example.agent\",\"apkVersionCode\":501,"
                + "\"apkSha256\":\"" + SHA256 + "\",\"canaryDeviceNumbers\":[\"h0001\",\"h0002\"]}";
        AuditLogRecord record = DeviceCommandAuditTest.audit("POST", ROLLOUT, body, null);

        assertEquals("plugin.audit.action.agent.rollout.start", record.getAction());
        JsonNode stored = storedBody(record);
        assertEquals("0.5.1", stored.get("targetVersion").asText());
        assertEquals("com.example.agent", stored.get("packageName").asText());
        assertEquals(501, stored.get("apkVersionCode").asInt());
        assertEquals(SHA256, stored.get("apkSha256").asText());
        assertEquals("[\"h0001\",\"h0002\"]", stored.get("canaryDeviceNumbers").toString());
    }

    @Test
    public void auditRecordForPromotingARolloutNamesTheRollout() throws IOException, ServletException {
        AuditLogRecord record = DeviceCommandAuditTest.audit("POST", ROLLOUT + "/3/promote", "", null);

        assertEquals("plugin.audit.action.agent.rollout.promote", record.getAction());
        assertTrue(record.getPayload(), record.getPayload().contains("URI: " + ROLLOUT + "/3/promote"));
    }

    @Test
    public void auditRecordForStoppingARolloutNamesTheRollout() throws IOException, ServletException {
        AuditLogRecord record = DeviceCommandAuditTest.audit("POST", ROLLOUT + "/3/cancel", "", null);

        assertEquals("plugin.audit.action.agent.rollout.cancel", record.getAction());
        assertTrue(record.getPayload(), record.getPayload().contains("URI: " + ROLLOUT + "/3/cancel"));
    }

    @Test
    public void aRefusedRolloutRequestIsRecordedAsFailed() throws IOException, ServletException {
        // A second rollout while one is running, or a promote after a cancel: the resource answers 200 with ERROR.
        for (String uri : new String[]{ROLLOUT, ROLLOUT + "/3/promote", ROLLOUT + "/3/cancel"}) {
            byte[] body = (uri.equals(ROLLOUT) ? "{\"targetVersion\":\"0.5.1\"}" : "").getBytes(StandardCharsets.UTF_8);
            ResourceAuditInfo info = ResourceAuditInfo.findAuditInfo("POST", uri).get();
            FilterChain refused = (req, res) -> {
                ((HttpServletResponse) res).setStatus(200);
                res.getOutputStream().write("{\"status\":\"ERROR\",\"message\":\"error.rollout.active\"}"
                        .getBytes(StandardCharsets.UTF_8));
            };
            ResourceAuditor auditor = info.getResourceAuditor(DeviceCommandAuditTest.request("POST", uri, body, null),
                    DeviceCommandAuditTest.response(), refused, "", "X-Real-IP");
            auditor.doProcess();
            AuditLogRecord record = auditor.getAuditLogRecord();

            assertEquals(uri, info.getAuditLogAction(), record.getAction());
            assertEquals(uri, Integer.valueOf(1), record.getErrorCode());
        }
    }

    /** The request body as the record stores it. */
    private static JsonNode storedBody(AuditLogRecord record) throws IOException {
        String payload = record.getPayload();
        int start = payload.indexOf("\nBody: ") + "\nBody: ".length();
        int end = payload.indexOf("\nUser-Agent: ");
        return new ObjectMapper().readTree(payload.substring(start, end));
    }
}
