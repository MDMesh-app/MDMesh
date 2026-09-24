package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Read-only helpers over a stored agent-v1 command payload ({@code agentCommand.payload}). The column is free
 * TEXT — the admin queue endpoint stores whatever string it is given (incl. '' and non-JSON) — so every helper
 * here is total: it never throws and answers null for anything it cannot read.
 */
public final class CommandPayloads {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CommandPayloads() {}

    /**
     * The payload's top-level {@code packageName} exactly as the agent receives it (NOT trimmed: the agent does
     * not trim either, so {@code " com.a "} and {@code "com.a"} are different targets), or null when the payload
     * is null/blank/not JSON/not an object, or {@code packageName} is absent, not a string, or blank.
     */
    public static String packageNameOf(String payloadJson) {
        if (payloadJson == null || payloadJson.trim().isEmpty()) return null;
        try {
            JsonNode pkg = MAPPER.readTree(payloadJson).path("packageName");
            if (!pkg.isTextual() || pkg.asText().trim().isEmpty()) return null;
            return pkg.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
