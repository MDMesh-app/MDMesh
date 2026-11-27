/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * <p>Request body for {@code POST /public/agent/v1/checkin}.</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentCheckInRequest {

    /**
     * The version the agent speaks. Agents send it from protocol 1.12 on; null from older ones, which never sent it
     * (their JSON leaves out defaults).
     */
    private String protocolVersion;

    private String deviceId;

    /** Stable, permission-free device identifier (enrollment-specific id / ANDROID_ID). */
    private String hardwareId;

    private AgentCapabilities capabilities;

    private List<AgentCommandResult> results;

    private AgentDeviceState state;

    /** Full device census (opaque JSON tree, stored as-is). */
    private com.fasterxml.jackson.databind.JsonNode telemetry;

    /** Buffered lifecycle events flushed on this check-in. */
    private List<AgentTelemetryEvent> events;

    public AgentCheckInRequest() {
    }
}
