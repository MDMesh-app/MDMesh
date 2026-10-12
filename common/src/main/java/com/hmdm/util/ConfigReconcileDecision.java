package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.AgentCommand;
import java.util.Set;

/**
 * Pure decision: should a device get a {@code config.apply} now? Kept free of DAOs so it is unit-testable;
 * {@code ConfigReconciler} (server) gathers the inputs and performs the insert.
 */
public final class ConfigReconcileDecision {
    public enum Action { NONE, ENQUEUE }
    /** After a terminal attempt (done/failed/unsupported/expired) for the SAME revision, wait this long before retrying. */
    public static final long RETRY_BACKOFF_MS = 60L * 60L * 1000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigReconcileDecision() {}

    public static Action decide(Set<String> deviceTokens, String currentRevision, String appliedRevision,
                                boolean hasOpenCommand, AgentCommand latest, long now) {
        if (!AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, deviceTokens)) return Action.NONE;
        if (currentRevision == null || currentRevision.equals(appliedRevision)) return Action.NONE;
        if (hasOpenCommand) return Action.NONE;
        if (latest != null && currentRevision.equals(revisionOf(latest))) {
            Long done = latest.getCompletedAt();
            long ref = done != null ? done : (latest.getCreatedAt() == null ? 0L : latest.getCreatedAt());
            if (now - ref < RETRY_BACKOFF_MS) return Action.NONE;
        }
        return Action.ENQUEUE;
    }

    public static String revisionOf(AgentCommand cmd) {
        if (cmd == null || cmd.getPayload() == null) return null;
        try {
            JsonNode n = MAPPER.readTree(cmd.getPayload()).get("revision");
            return n == null || n.isNull() ? null : n.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
