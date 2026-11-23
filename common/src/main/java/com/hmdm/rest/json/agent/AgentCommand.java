/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>Server -&gt; device command envelope. {@code type} and {@code payload} are entirely opaque to
 * the server: it never switches on them. Delivery is gated solely by {@code requiresCapability}
 * set-membership against the device's flattened capability tokens.</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentCommand {

    private String protocolVersion = AgentProtocol.VERSION;

    private String commandId;

    private String type;

    private String issuedAt;

    private Integer ttlSeconds;

    private String requiresCapability;

    private JsonNode payload;

    public AgentCommand() {
    }
}
