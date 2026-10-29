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
 * Enhanced Factory Reset Protection (EFRP) section of {@link DesiredConfig} (protocol 1.5): whether a wiped
 * device demands an authorized Google account, and the account allow-list permitted to unlock it. Applied by
 * the agent through {@code DevicePolicyManager.setFactoryResetProtectionPolicy} (API 30+, Device Owner).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredFactoryResetProtection {
    /** true = FRP required after a wipe; false = FRP explicitly disabled. */
    private Boolean enabled;
    /** Google account ids allowed to bypass FRP at setup; omitted/empty = the platform default (last account). */
    private List<String> accounts;
}
