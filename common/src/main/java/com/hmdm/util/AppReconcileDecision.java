/*
 * MDMesh agent-v1: which configuration apps a device still needs.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.AgentCommand;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure decision: does a configuration app still need an {@code app.install} on a device, judged from the device's
 * {@code app.install} and {@code app.uninstall} history? Kept free of DAOs so it is unit-testable;
 * {@code AppReconciler} (server) gathers the inputs and performs the insert. Only the versions a configuration change
 * added to the device's set are decided: one already in the set it was last reconciled to was installed then,
 * or left to the admin.
 *
 * <p>An install that reached the device and failed (or expired: its result was lost) is retried after
 * {@link #backoffMs} ({@link #FIRST_RETRY_MS}, then four times longer each time), up to {@link #MAX_ATTEMPTS}
 * attempts in all; then it is left for the admin (Install config apps re-sends it). One that never reached the device
 * and failed was refused by the server (no SHA-256 for the APK), so it is not retried either.</p>
 */
public final class AppReconcileDecision {

    public enum State {
        /** Installed: an install of this version, or a newer one, came back done (and no uninstall since). */
        INSTALLED,
        /** An install of this version is pending or delivered. */
        IN_FLIGHT,
        /** Queue an install now. */
        QUEUE,
        /** The last attempt failed; wait before the next. */
        BACK_OFF,
        /** Not retried: it failed {@link #MAX_ATTEMPTS} times, the server refused it, or the agent can't install. */
        GAVE_UP
    }

    public static final String UNINSTALL_TYPE = "app.uninstall";
    /** Install attempts that reached the device, per app version, after which a failed one is no longer retried. */
    public static final int MAX_ATTEMPTS = 3;
    /** Wait after the first failed attempt; each further one waits four times longer. */
    public static final long FIRST_RETRY_MS = 15L * 60L * 1000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AppReconcileDecision() {}

    /**
     * The revision of a configuration's install set: its action=install version ids, in any order, as the sorted,
     * comma-separated list {@code device_state.appsRevision} stores. A list, not a hash, so a change shows which
     * versions it added ({@link #versionIds}).
     */
    public static String revision(Collection<Integer> installVersionIds) {
        StringBuilder out = new StringBuilder();
        for (Integer id : new TreeSet<Integer>(installVersionIds)) {
            if (out.length() > 0) out.append(',');
            out.append(id);
        }
        return out.toString();
    }

    /** The version ids a stored revision lists (entries that aren't numbers are skipped); empty for null. */
    public static Set<Integer> versionIds(String revision) {
        Set<Integer> ids = new HashSet<Integer>();
        if (revision == null) return ids;
        for (String part : revision.split(",")) {
            try {
                ids.add(Integer.valueOf(part.trim()));
            } catch (NumberFormatException e) {
                // not a version id: ignored
            }
        }
        return ids;
    }

    /**
     * @param pkg         the app's package name
     * @param versionCode the configuration's version of it (0 or less = any version)
     * @param history     the device's app.install and app.uninstall commands, newest first
     */
    public static State decide(String pkg, int versionCode, List<AgentCommand> history, long now) {
        AgentCommand latest = null;   // the newest install of this version
        int attempts = 0;             // installs of this version that reached the device and failed or expired
        for (AgentCommand c : history) {
            JsonNode p = c == null ? null : payload(c);
            if (p == null || pkg == null || !pkg.equals(p.path("packageName").asText(null))) continue;
            if (UNINSTALL_TYPE.equals(c.getType())) {
                // Removed from the device: the installs before it no longer count.
                if ("done".equals(c.getStatus())) break;
                continue;
            }
            if (!InstallIntegrity.INSTALL_TYPE.equals(c.getType())) continue;
            int vc = p.path("versionCode").asInt(0);
            if ("done".equals(c.getStatus()) && (versionCode <= 0 || vc >= versionCode)) return State.INSTALLED;
            if (versionCode > 0 && vc != versionCode) continue;
            if (latest == null) latest = c;
            if (c.getDeliveredAt() != null && ("failed".equals(c.getStatus()) || "expired".equals(c.getStatus()))) {
                attempts++;
            }
        }
        if (latest == null) return State.QUEUE;
        String status = latest.getStatus();
        if ("pending".equals(status) || "delivered".equals(status)) return State.IN_FLIGHT;
        if ("unsupported".equals(status)) return State.GAVE_UP;
        if (latest.getDeliveredAt() == null) {
            // Expired before reaching the device (it was offline): send it again. Failed there: the server refused it.
            return "expired".equals(status) ? State.QUEUE : State.GAVE_UP;
        }
        if (attempts >= MAX_ATTEMPTS) return State.GAVE_UP;
        Long done = latest.getCompletedAt();
        long ref = done != null ? done : latest.getDeliveredAt();
        return now - ref < backoffMs(attempts) ? State.BACK_OFF : State.QUEUE;
    }

    /** The wait after {@code attempts} failed attempts: 15 min after the first, 1 h after the second. */
    public static long backoffMs(int attempts) {
        return FIRST_RETRY_MS << (2 * Math.max(0, Math.min(attempts, MAX_ATTEMPTS) - 1));
    }

    /** True when nothing more will happen without a configuration change: every app is installed or given up on. */
    public static boolean settled(State state) {
        return state == State.INSTALLED || state == State.GAVE_UP;
    }

    private static JsonNode payload(AgentCommand c) {
        if (c.getPayload() == null) return null;
        try {
            return MAPPER.readTree(c.getPayload());
        } catch (Exception e) {
            return null;
        }
    }
}
