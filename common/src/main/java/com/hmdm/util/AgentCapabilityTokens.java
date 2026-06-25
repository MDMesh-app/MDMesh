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

package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * <p>Flattens an opaque agent capability matrix into a flat set of capability <i>tokens</i>, and
 * gates command delivery on token set-membership. This is the entire gate for the v1 agent
 * protocol &mdash; it is deliberately type-agnostic: the server never enumerates command types.</p>
 *
 * <p>Token forms (see {@code proto/endpoints.md}):</p>
 * <ul>
 *     <li>each {@code capabilities.policy[]} entry &rarr; {@code policy.<key>}</li>
 *     <li>each {@code capabilities.appManagement[]} entry &rarr; {@code app.<key>}</li>
 *     <li>{@code capabilities.remoteControl.tier} (when not {@code none}) &rarr; {@code remote.<tier>}</li>
 *     <li>{@code capabilities.oem.knox == true} &rarr; {@code oem.knox}</li>
 * </ul>
 */
public final class AgentCapabilityTokens {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentCapabilityTokens() {
    }

    /**
     * <p>Flattens the capability matrix JSON (the {@code capabilities} object) into its token set.
     * Tolerant of null, empty, malformed, or unknown structure: anything it cannot interpret is
     * simply skipped, never throwing.</p>
     *
     * @param capabilitiesJson the raw {@code capabilities} object as stored on the device, or null.
     * @return an unmodifiable set of capability tokens (possibly empty, never null).
     */
    public static Set<String> flatten(String capabilitiesJson) {
        if (capabilitiesJson == null || capabilitiesJson.trim().isEmpty()) {
            return Collections.emptySet();
        }
        try {
            return flatten(MAPPER.readTree(capabilitiesJson));
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    /**
     * <p>Flattens the capability matrix tree (the {@code capabilities} object) into its token set.</p>
     *
     * @param capabilities the {@code capabilities} JSON node, or null.
     * @return an unmodifiable set of capability tokens (possibly empty, never null).
     */
    public static Set<String> flatten(JsonNode capabilities) {
        if (capabilities == null || !capabilities.isObject()) {
            return Collections.emptySet();
        }

        Set<String> tokens = new LinkedHashSet<>();

        addArrayTokens(tokens, capabilities.get("policy"), "policy.");
        addArrayTokens(tokens, capabilities.get("appManagement"), "app.");
        addArrayTokens(tokens, capabilities.get("device"), "device.");

        JsonNode remoteControl = capabilities.get("remoteControl");
        if (remoteControl != null && remoteControl.isObject()) {
            JsonNode tier = remoteControl.get("tier");
            if (tier != null && tier.isTextual()) {
                String tierValue = tier.asText();
                if (!tierValue.isEmpty() && !"none".equals(tierValue)) {
                    tokens.add("remote." + tierValue);
                }
            }
        }

        JsonNode oem = capabilities.get("oem");
        if (oem != null && oem.isObject()) {
            JsonNode knox = oem.get("knox");
            if (knox != null && knox.isBoolean() && knox.asBoolean()) {
                tokens.add("oem.knox");
            }
        }

        return Collections.unmodifiableSet(tokens);
    }

    private static void addArrayTokens(Set<String> tokens, JsonNode array, String prefix) {
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                if (item != null && item.isTextual()) {
                    String value = item.asText();
                    if (!value.isEmpty()) {
                        tokens.add(prefix + value);
                    }
                }
            }
        }
    }

    /**
     * <p>The gate: a command is allowed to a device iff it requires no capability, or the required
     * capability is present in the device's flattened token set.</p>
     *
     * @param requiresCapability the command's required capability token, or null for ungated.
     * @param deviceTokens       the device's current capability token set (treated as empty if null).
     * @return {@code true} if the command may be delivered.
     */
    public static boolean isAllowed(String requiresCapability, Set<String> deviceTokens) {
        if (requiresCapability == null || requiresCapability.isEmpty()) {
            return true;
        }
        return deviceTokens != null && deviceTokens.contains(requiresCapability);
    }
}
