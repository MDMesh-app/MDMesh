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
        return decide(deviceTokens, currentRevision, appliedRevision, hasOpenCommand, latest, now, false);
    }

    /**
     * @param agentChanged the device runs a different agent version than the one its configuration was last sent
     *                     to ({@link #agentChanged}). A newer agent may support keys the old one reported
     *                     {@code unsupported}, so the document is re-sent once even when the revision is in sync,
     *                     and the same-revision backoff does not apply (the caller records the version on enqueue,
     *                     which makes it fire once per version change).
     */
    public static Action decide(Set<String> deviceTokens, String currentRevision, String appliedRevision,
                                boolean hasOpenCommand, AgentCommand latest, long now, boolean agentChanged) {
        if (!AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, deviceTokens)) return Action.NONE;
        if (currentRevision == null) return Action.NONE;
        if (currentRevision.equals(appliedRevision) && !agentChanged) return Action.NONE;
        if (hasOpenCommand) return Action.NONE;
        if (agentChanged) return Action.ENQUEUE;
        if (latest != null && currentRevision.equals(revisionOf(latest))) {
            Long done = latest.getCompletedAt();
            long ref = done != null ? done : (latest.getCreatedAt() == null ? 0L : latest.getCreatedAt());
            if (now - ref < RETRY_BACKOFF_MS) return Action.NONE;
        }
        return Action.ENQUEUE;
    }

    /** True when both versions are known and differ; an unknown baseline is recorded, never treated as a change. */
    public static boolean agentChanged(String agentVersion, String configAgentVersion) {
        return agentVersion != null && configAgentVersion != null && !agentVersion.equals(configAgentVersion);
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
