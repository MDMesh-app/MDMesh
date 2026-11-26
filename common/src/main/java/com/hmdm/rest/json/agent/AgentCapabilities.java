/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * <p>The device capability matrix. The server treats it as <b>opaque</b>: it stores and forwards
 * the raw JSON tree and only flattens it into capability tokens for gating (see
 * {@link com.hmdm.util.AgentCapabilityTokens}). Unknown keys are preserved verbatim so a newer
 * agent can advertise capabilities an older server never heard of.</p>
 */
@JsonDeserialize(using = AgentCapabilities.Deserializer.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentCapabilities {

    private final JsonNode tree;

    public AgentCapabilities() {
        this.tree = null;
    }

    public AgentCapabilities(JsonNode tree) {
        this.tree = tree;
    }

    /**
     * <p>The raw capability tree, exactly as posted by the device.</p>
     */
    @JsonValue
    public JsonNode getTree() {
        return tree;
    }

    /**
     * <p>Custom deserializer so an inbound JSON object binds straight into the opaque tree
     * instead of requiring a fixed Java shape.</p>
     */
    public static class Deserializer extends com.fasterxml.jackson.databind.JsonDeserializer<AgentCapabilities> {
        @Override
        public AgentCapabilities deserialize(com.fasterxml.jackson.core.JsonParser p,
                                             com.fasterxml.jackson.databind.DeserializationContext ctxt)
                throws java.io.IOException {
            JsonNode node = p.getCodec().readTree(p);
            return new AgentCapabilities(node);
        }
    }
}
