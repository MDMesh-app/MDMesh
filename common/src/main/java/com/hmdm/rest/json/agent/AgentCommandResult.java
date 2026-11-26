/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>Device -&gt; server ack/outcome for a previously-delivered command. The {@code status} drives
 * the queued command's stored status (e.g. {@code done}, {@code failed}, {@code unsupported}).</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentCommandResult {

    private String protocolVersion = AgentProtocol.VERSION;

    private String commandId;

    private String status;

    private String detail;

    private String completedAt;

    public AgentCommandResult() {
    }
}
