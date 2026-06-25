/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
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

    private String protocolVersion = AgentProtocol.VERSION;

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
