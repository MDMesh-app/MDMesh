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
 * <p>{@code data} payload of the Response envelope for {@code POST /public/agent/v1/checkin}.
 * Carries the next batch of capability-gated commands for the device.</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentCheckInResponse {

    private String protocolVersion = AgentProtocol.VERSION;

    private List<AgentCommand> commands;

    /**
     * Every {@code app.install} this server queues carries the APK's SHA-256 ({@code InstallIntegrity}), so the agent
     * may refuse one without. Additive (protocol 1.3): older agents ignore it.
     */
    private String installIntegrity = INSTALL_INTEGRITY_REQUIRED;

    public static final String INSTALL_INTEGRITY_REQUIRED = "required";

    /**
     * How many of the request's {@code results}, counted from the first, the server took. The agent keeps the rest
     * and sends them again on its next check-in. Older agents ignore it.
     */
    private Integer resultsAccepted;

    /**
     * How many of the request's {@code events}, counted from the first, the server consumed (stored, or skipped as
     * invalid). The agent drops those and sends the rest on its next check-in. Additive (protocol 1.12): older agents
     * ignore it.
     */
    private Integer eventsAccepted;

    public AgentCheckInResponse() {
    }

    public AgentCheckInResponse(List<AgentCommand> commands) {
        this.commands = commands;
    }
}
