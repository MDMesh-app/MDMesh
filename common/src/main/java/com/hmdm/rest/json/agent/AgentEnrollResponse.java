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

    public AgentEnrollResponse() {
    }

    public AgentEnrollResponse(String deviceId, String configurationName, String deviceSecret) {
        this.deviceId = deviceId;
        this.configurationName = configurationName;
        this.deviceSecret = deviceSecret;
    }
}
