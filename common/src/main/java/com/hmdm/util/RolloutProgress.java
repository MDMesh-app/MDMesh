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
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.RolloutDeviceRow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>Pure classification of a device's status within a staged agent-APK rollout. Progress is derived
 * from the device-reported {@code agentVersion} (the source of truth — it survives the self-update
 * process restart) plus capability eligibility, never from a fragile per-device command ack.</p>
 */
public final class RolloutProgress {

    /**
     * The capability token an agent must advertise to receive an {@code app.install}. Per
     * {@code proto/endpoints.md} an {@code appManagement[]} entry {@code silentInstall} flattens to the
     * prefixed token {@code app.silentInstall} — which is what the delivery gate matches, so the
     * command's {@code requiresCapability} and this eligibility check both use the prefixed form.
     */
    public static final String INSTALL_CAPABILITY = "app.silentInstall";

    public enum Status { UPDATED, INELIGIBLE, PENDING, OUTSTANDING }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RolloutProgress() {
    }

    /** Classify one device against the rollout target. {@code hasPending} = it has an outstanding
     *  (pending/delivered) app.install for this rollout. */
    public static Status classify(String targetVersion, RolloutDeviceRow row, boolean hasPending) {
        String version = row == null ? null : row.getAgentVersion();
        if (targetVersion != null && targetVersion.equals(version)) {
            return Status.UPDATED;
        }
        Set<String> tokens = AgentCapabilityTokens.flatten(row == null ? null : row.getCapabilitiesJson());
        if (!AgentCapabilityTokens.isAllowed(INSTALL_CAPABILITY, tokens)) {
            return Status.INELIGIBLE; // too old / can't self-install — the gate would block the command
        }
        return hasPending ? Status.PENDING : Status.OUTSTANDING;
    }

    /** Device numbers whose in-flight app.install is for {@code packageName}. Any other install (an F-Droid or Library
     *  app) does not mean the device is installing the update. Payloads are opaque text, so a malformed one is skipped. */
    public static Set<String> inFlightFor(String packageName, List<AgentCommand> inFlight) {
        Set<String> out = new HashSet<>();
        if (inFlight == null) {
            return out;
        }
        for (AgentCommand c : inFlight) {
            if (isFor(packageName, c)) {
                out.add(c.getDeviceNumber());
            }
        }
        return out;
    }

    /** Whether the app.install {@code c} installs {@code packageName}. Payloads are opaque text: a malformed one is not. */
    public static boolean isFor(String packageName, AgentCommand c) {
        if (packageName == null || c == null || c.getPayload() == null) {
            return false;
        }
        try {
            JsonNode p = MAPPER.readTree(c.getPayload());
            return p != null && packageName.equals(p.path("packageName").asText(null));
        } catch (Exception e) {
            return false;
        }
    }

    /** Install attempts per device per rollout after which a failed one is no longer retried. */
    public static final int MAX_ATTEMPTS = 3;

    /** Whether an OUTSTANDING device gets the update queued again, given its newest command for this rollout (null =
     *  none, and whether that one ever reached the device) and how many of this rollout's commands reached it. A
     *  never-queued one is sent, and so is one that expired undelivered (the device was offline). One that reached the
     *  device and failed, or expired (its result was lost: the agent died mid-install, say), is retried up to
     *  {@link #MAX_ATTEMPTS} attempts in all, so a device that keeps failing, or installs but never reports the target
     *  version, is not reinstalled forever. */
    public static boolean shouldRequeue(String latestStatus, boolean latestDelivered, int deliveredAttempts) {
        if (latestStatus == null || ("expired".equals(latestStatus) && !latestDelivered)) {
            return true;
        }
        return ("expired".equals(latestStatus) || "failed".equals(latestStatus)) && deliveredAttempts < MAX_ATTEMPTS;
    }

    /** Tally a cohort. {@code pendingDeviceNumbers} = the set with an outstanding app.install. */
    public static Counts counts(String targetVersion, List<RolloutDeviceRow> rows, Set<String> pendingDeviceNumbers) {
        Counts c = new Counts();
        if (rows == null) {
            return c;
        }
        for (RolloutDeviceRow row : rows) {
            c.total++;
            boolean hasPending = pendingDeviceNumbers != null && row != null
                    && pendingDeviceNumbers.contains(row.getDeviceNumber());
            switch (classify(targetVersion, row, hasPending)) {
                case UPDATED:     c.updated++; break;
                case INELIGIBLE:  c.ineligible++; break;
                case PENDING:     c.pending++; break;
                default:          c.outstanding++; break;
            }
        }
        return c;
    }

    /** Serializable count bucket (Jackson reads the getters). */
    public static final class Counts {
        private int total;
        private int updated;
        private int pending;
        private int outstanding;
        private int ineligible;

        public int getTotal() { return total; }
        public int getUpdated() { return updated; }
        public int getPending() { return pending; }
        public int getOutstanding() { return outstanding; }
        public int getIneligible() { return ineligible; }
    }
}
