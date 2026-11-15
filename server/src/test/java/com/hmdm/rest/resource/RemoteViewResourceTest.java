package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.Assert.*;

public class RemoteViewResourceTest {
    @Test public void agentPayloadMatchesTheVersionedAgentContract() throws Exception {
        UUID id = UUID.randomUUID(); long expiry = 1_700_000_000_000L;
        JsonNode payload = new ObjectMapper().readTree(RemoteViewResource.agentPayload(
                id, expiry, "https://mdm.example/agent/remote/" + id,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertEquals(id.toString(), payload.path("sessionId").asText());
        assertEquals("view", payload.path("mode").asText());
        assertEquals(Instant.ofEpochMilli(expiry).toString(), payload.path("expiresAt").asText());
        assertEquals("https://mdm.example/agent/remote/" + id, payload.path("signaling").path("endpoint").asText());
        assertEquals(64, payload.path("signaling").path("credential").asText().length());
        assertFalse(payload.has("joinSecret"));
    }
}
