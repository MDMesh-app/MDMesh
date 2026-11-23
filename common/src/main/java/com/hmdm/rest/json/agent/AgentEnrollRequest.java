/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>Request body for {@code POST /public/agent/v1/enroll}.</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentEnrollRequest {

    private String protocolVersion = AgentProtocol.VERSION;

    private String enrollToken;

    /** Stable, permission-free device identifier (enrollment-specific id / ANDROID_ID). */
    private String hardwareId;

    /**
     * Optional (agents from 0.5): a random id the agent keeps until its first check-in succeeds, sent again on every
     * retry of the same enrollment. A retry whose response was lost gets the device the first request created.
     */
    private String enrollAttempt;

    /**
     * Optional (agents from 0.5): true on the agent's credential recovery. The server then only resumes the device
     * {@link #enrollAttempt} already created and has not checked in yet; otherwise it answers
     * {@code error.agent.enrollment.notResumable} and takes no use of the code.
     */
    private Boolean resumeOnly;

    private AgentInfo agent;

    private AgentDeviceInfo device;

    private AgentCapabilities capabilities;

    public AgentEnrollRequest() {
    }
}
