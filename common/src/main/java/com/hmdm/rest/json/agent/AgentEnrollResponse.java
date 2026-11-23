/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>{@code data} payload of the Response envelope for {@code POST /public/agent/v1/enroll}.</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentEnrollResponse {

    private String protocolVersion = AgentProtocol.VERSION;

    private String deviceId;

    private String configurationName;

    /**
     * Per-device secret minted at enrollment. The agent stores it and presents it as
     * {@code Authorization: Bearer <deviceSecret>} on every /checkin. Only the SHA-256
     * hash is kept server-side; this plaintext is returned exactly once, here.
     */
    private String deviceSecret;

    /**
     * True from servers that resume enrollments (protocol 1.9): the agent may then recover refused credentials with a
     * resume-only enroll of the same attempt. Absent from older servers.
     */
    private Boolean resumeSupported;

    public AgentEnrollResponse() {
    }

    public AgentEnrollResponse(String deviceId, String configurationName, String deviceSecret) {
        this.deviceId = deviceId;
        this.configurationName = configurationName;
        this.deviceSecret = deviceSecret;
    }
}
