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
    /**
     * Detail of a config.apply the server ended because the device checked in again without its result. The agent
     * runs commands inside its check-in and reports them on the next one, so such a command is not running: the
     * agent restarted mid-apply or never got the response. A late result from the device still overwrites it.
     */
    public static final String ABANDONED_DETAIL = "no result: the agent checked in again without finishing it";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigReconcileDecision() {}

    public static Action decide(Set<String> deviceTokens, String currentRevision, String appliedRevision,
                                boolean hasOpenCommand, AgentCommand latest, long now) {
        return decide(deviceTokens, currentRevision, appliedRevision, hasOpenCommand, latest, now, false);
    }

    /**
     * @param agentChanged the device runs a different agent version than the one that last completed config.apply
     *                     ({@link #agentChanged}). A newer agent may support keys the old one reported
     *                     {@code unsupported}, so the document is re-sent even when the revision is in sync, and the
     *                     old agent's DONE does not hold it back. A failed/expired attempt keeps the normal backoff.
     *                     The version is recorded when the re-sent command comes back done, so this ends there.
     */
    public static Action decide(Set<String> deviceTokens, String currentRevision, String appliedRevision,
                                boolean hasOpenCommand, AgentCommand latest, long now, boolean agentChanged) {
        return decide(deviceTokens, currentRevision, appliedRevision, hasOpenCommand, latest, null, now, agentChanged);
    }

    /**
     * @param previous the config.apply before {@code latest} (null when none or unknown). An {@link #abandoned}
     *                 latest attempt is sent again at once, unless the one before it was abandoned for the same
     *                 revision too: a document that kills the agent then gets the normal backoff, not a re-send per
     *                 check-in.
     */
    public static Action decide(Set<String> deviceTokens, String currentRevision, String appliedRevision,
                                boolean hasOpenCommand, AgentCommand latest, AgentCommand previous, long now,
                                boolean agentChanged) {
        if (!AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, deviceTokens)) return Action.NONE;
        if (currentRevision == null) return Action.NONE;
        if (currentRevision.equals(appliedRevision) && !agentChanged) return Action.NONE;
        if (hasOpenCommand) return Action.NONE;
        boolean doneByOldAgent = agentChanged && latest != null && "done".equals(latest.getStatus());
        boolean firstAbandoned = abandoned(latest) && !(abandoned(previous) && currentRevision.equals(revisionOf(previous)));
        if (latest != null && currentRevision.equals(revisionOf(latest)) && !doneByOldAgent && !firstAbandoned) {
            Long done = latest.getCompletedAt();
            long ref = done != null ? done : (latest.getCreatedAt() == null ? 0L : latest.getCreatedAt());
            if (now - ref < RETRY_BACKOFF_MS) return Action.NONE;
        }
        return Action.ENQUEUE;
    }

    /** True when the server ended this command because the device checked in without its result. */
    public static boolean abandoned(AgentCommand cmd) {
        return cmd != null && "expired".equals(cmd.getStatus()) && ABANDONED_DETAIL.equals(cmd.getDetail());
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
