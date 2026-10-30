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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure decision: does a configuration app still need an {@code app.install} on a device, judged from the device's
 * {@code app.install} and {@code app.uninstall} history? Kept free of DAOs so it is unit-testable;
 * {@code AppReconciler} (server) gathers the inputs and performs the insert. Only the versions a change added to the
 * device's set are decided, or its whole set when the device has none recorded for its configuration (enrolled after
 * the update, or moved to another configuration).
 *
 * <p>An install that reached the device and didn't come back done (failed, unsupported, or expired: its result was
 * lost) is retried after {@link #backoffMs} ({@link #FIRST_RETRY_MS}, then four times longer each time), up to
 * {@link #MAX_ATTEMPTS} attempts in all; then it is left for the admin (Install config apps re-sends it). One that never
 * reached the device and failed was refused by the server (no SHA-256 for the APK), so it is not retried either. Only
 * attempts from the last {@link #ATTEMPT_WINDOW_MS} count: the whole retry ladder takes about 1 h 15 min, so an app
 * that failed long ago (removed from the configuration, then added again) is tried afresh.</p>
 */
public final class AppReconcileDecision {

    public enum State {
        /** Installed: an install of this version, or a newer one, came back done (and no uninstall since). */
        INSTALLED,
        /** An install of this version is pending, delivered or accepted. */
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
    /** Attempts older than this don't count: a day, far longer than the retry ladder. */
    public static final long ATTEMPT_WINDOW_MS = 24L * 60L * 60L * 1000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AppReconcileDecision() {}

    /**
     * The revision of a configuration's install set, as {@code device_state.appsRevision} stores it: the configuration
     * id, ':', then its action=install version ids sorted as numbers, distinct and comma-separated ({@code "5:10,12"};
     * {@code "5:"} when it has none). The Liquibase changeSet that added the column builds the same text. A list, not
     * a hash, so a change shows which versions it added ({@link #versionIds}).
     */
    public static String revision(Integer configurationId, Collection<Integer> installVersionIds) {
        StringBuilder out = new StringBuilder().append(configurationId).append(':');
        boolean first = true;
        for (Integer id : new TreeSet<Integer>(installVersionIds)) {
            if (!first) out.append(',');
            out.append(id);
            first = false;
        }
        return out.toString();
    }

    /** The configuration a stored revision is for, or null (none stored, or not one {@link #revision} wrote). */
    public static Integer configurationId(String revision) {
        int colon = revision == null ? -1 : revision.indexOf(':');
        if (colon < 0) return null;
        try {
            return Integer.valueOf(revision.substring(0, colon).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The version ids a stored revision lists (entries that aren't numbers are skipped); empty for null. */
    public static Set<Integer> versionIds(String revision) {
        Set<Integer> ids = new HashSet<Integer>();
        if (revision == null) return ids;
        for (String part : revision.substring(revision.indexOf(':') + 1).split(",")) {
            try {
                ids.add(Integer.valueOf(part.trim()));
            } catch (NumberFormatException e) {
                // not a version id: ignored
            }
        }
        return ids;
    }

    /** A device's app.install and app.uninstall commands, read once and grouped by package. */
    public static final class History {
        private final Map<String, List<Entry>> byPackage = new HashMap<String, List<Entry>>();

        /** @param newestFirst the commands as the history query returns them */
        public static History of(List<AgentCommand> newestFirst) {
            History h = new History();
            for (AgentCommand c : newestFirst) {
                JsonNode p = c == null ? null : payload(c);
                String pkg = p == null ? null : p.path("packageName").asText(null);
                if (pkg == null) continue;
                boolean uninstall = UNINSTALL_TYPE.equals(c.getType());
                if (!uninstall && !InstallIntegrity.INSTALL_TYPE.equals(c.getType())) continue;
                List<Entry> list = h.byPackage.get(pkg);
                if (list == null) h.byPackage.put(pkg, list = new ArrayList<Entry>());
                list.add(new Entry(uninstall, c.getStatus(), p.path("versionCode").asInt(0), c.getCreatedAt(),
                        c.getDeliveredAt(), c.getCompletedAt()));
            }
            return h;
        }

        List<Entry> of(String pkg) {
            List<Entry> list = pkg == null ? null : byPackage.get(pkg);
            return list == null ? Collections.<Entry>emptyList() : list;
        }
    }

    static final class Entry {
        final boolean uninstall;
        final String status;
        final int versionCode;
        final Long createdAt;
        final Long deliveredAt;
        final Long completedAt;

        Entry(boolean uninstall, String status, int versionCode, Long createdAt, Long deliveredAt, Long completedAt) {
            this.uninstall = uninstall;
            this.status = status;
            this.versionCode = versionCode;
            this.createdAt = createdAt;
            this.deliveredAt = deliveredAt;
            this.completedAt = completedAt;
        }
    }

    /** {@link #decide(String, int, History, long)} over a raw history (newest first). */
    public static State decide(String pkg, int versionCode, List<AgentCommand> history, long now) {
        return decide(pkg, versionCode, History.of(history), now);
    }

    /**
     * @param pkg         the app's package name
     * @param versionCode the configuration's version of it (0 or less = any version)
     */
    public static State decide(String pkg, int versionCode, History history, long now) {
        Entry latest = null;   // the newest install of this version in the window
        int attempts = 0;      // installs of this version in the window that reached the device and didn't finish
        for (Entry e : history.of(pkg)) {
            if (e.uninstall) {
                // Removed from the device: the installs before it no longer count.
                if ("done".equals(e.status)) break;
                continue;
            }
            if ("done".equals(e.status) && (versionCode <= 0 || e.versionCode >= versionCode)) return State.INSTALLED;
            if (versionCode > 0 && e.versionCode != versionCode) continue;
            if (e.createdAt != null && now - e.createdAt > ATTEMPT_WINDOW_MS) continue;
            if (latest == null) {
                latest = e;
                if (inFlight(e.status)) return State.IN_FLIGHT;
            }
            if (e.deliveredAt != null && !"done".equals(e.status)) attempts++;
        }
        if (latest == null) return State.QUEUE;
        if (latest.deliveredAt == null) {
            // Expired before reaching the device (it was offline): send it again. Failed there: the server refused it.
            return "expired".equals(latest.status) ? State.QUEUE : State.GAVE_UP;
        }
        if ("unsupported".equals(latest.status) || attempts >= MAX_ATTEMPTS) return State.GAVE_UP;
        long ref = latest.completedAt != null ? latest.completedAt : latest.deliveredAt;
        return now - ref < backoffMs(attempts) ? State.BACK_OFF : State.QUEUE;
    }

    /** Still on its way or running: pending, delivered, or accepted by the agent. */
    static boolean inFlight(String status) {
        return "pending".equals(status) || "delivered".equals(status) || "accepted".equals(status);
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
