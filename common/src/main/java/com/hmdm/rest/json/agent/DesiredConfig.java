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
import java.util.Map;

/**
 * Desired-state document carried by the {@code config.apply} command (proto/payloads/config-apply.schema.json).
 * Built by {@link com.hmdm.util.DesiredConfigBuilder}; never hand-assembled elsewhere.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredConfig {
    private String revision;
    private Integer configurationId;
    /** Only keys the configuration manages; true = allowed/enabled. */
    private Map<String, Boolean> policies;
    /**
     * Present = ensure kiosk with this payload. Absent (null) = the configuration does not assert kiosk; the agent
     * exits only if the previously applied configuration document had kiosk.
     */
    private DesiredKiosk kiosk;
    private DesiredLocation location;
}
