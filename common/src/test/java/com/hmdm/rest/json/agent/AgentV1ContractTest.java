package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Backward-compatibility gate for the agent-facing v1 wire contract. Golden payloads under
 * resources/contract/v1/ are exactly what an old (v1.0) agent sends. If a future change to the
 * agent DTOs would stop an old APK from enrolling/checking in — a removed/renamed/now-required
 * field, or intolerance of a newer agent's extra fields — these tests fail the build.
 *
 * Pure JSON (no DB), so it runs in CI without a database.
 */
public class AgentV1ContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private <T> T load(String file, Class<T> type) throws Exception {
        return mapper.readValue(getClass().getResourceAsStream("/contract/v1/" + file), type);
    }

    @Test
    public void checkin_v1_loads_with_load_bearing_fields() throws Exception {
        AgentCheckInRequest r = load("checkin.json", AgentCheckInRequest.class);
        assertEquals("dev-1", r.getDeviceId());
        assertEquals("abc123", r.getHardwareId());
        assertNotNull("capabilities must parse", r.getCapabilities());
        assertNotNull("state must parse", r.getState());
        assertEquals("14", r.getState().getAndroidRelease());
        assertNotNull("results must parse", r.getResults());
        assertEquals("5", r.getResults().get(0).getCommandId());
        assertEquals("done", r.getResults().get(0).getStatus());
        assertNotNull("telemetry JsonNode must be preserved", r.getTelemetry());
    }

    @Test
    public void enroll_v1_loads_with_load_bearing_fields() throws Exception {
        AgentEnrollRequest r = load("enroll.json", AgentEnrollRequest.class);
        assertEquals("tok", r.getEnrollToken());
        assertEquals("abc123", r.getHardwareId());
        assertNotNull("capabilities must parse", r.getCapabilities());
    }

    /** A newer agent sends fields this server version doesn't know — must be tolerated, not 400. */
    @Test
    public void newer_agent_extra_fields_are_tolerated() throws Exception {
        AgentCheckInRequest r = load("checkin-future.json", AgentCheckInRequest.class);
        assertEquals("dev-1", r.getDeviceId());
        assertEquals("14", r.getState().getAndroidRelease());
    }

    /** Response fields the agent parses must keep their names. */
    @Test
    public void checkin_response_keeps_command_fields() throws Exception {
        AgentCommand cmd = mapper.readValue(
                "{\"commandId\":\"9\",\"type\":\"device.lock\"}", AgentCommand.class);
        AgentCheckInResponse resp = new AgentCheckInResponse(Collections.singletonList(cmd));
        String json = mapper.writeValueAsString(resp);
        assertTrue(json.contains("\"commandId\""));
        assertTrue(json.contains("\"type\""));
        assertTrue(json.contains("device.lock"));
        assertTrue(json.contains("\"commands\""));
    }

    @Test
    public void enroll_response_keeps_secret_fields() throws Exception {
        String json = mapper.writeValueAsString(new AgentEnrollResponse("dev-1", "Default", "s3cr3t"));
        assertTrue(json.contains("\"deviceId\""));
        assertTrue(json.contains("\"deviceSecret\""));
    }
}
