package com.hmdm.util;

import com.hmdm.persistence.domain.AgentCommand;
import org.junit.Test;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static com.hmdm.util.ConfigReconcileDecision.Action.*;
import static org.junit.Assert.*;

public class ConfigReconcileDecisionTest {
    private static final Set<String> CAPABLE = new HashSet<>(Collections.singletonList("device.configApply"));
    private static final long NOW = 1_000_000_000L;

    private static AgentCommand cmd(String revision, String status, Long completedAt) {
        AgentCommand c = new AgentCommand();
        c.setType("config.apply"); c.setStatus(status); c.setCompletedAt(completedAt);
        c.setPayload("{\"revision\":\"" + revision + "\",\"configurationId\":1}");
        return c;
    }

    @Test public void no_capability_means_none() {
        assertEquals(NONE, ConfigReconcileDecision.decide(Collections.<String>emptySet(), "r1", null, false, null, NOW));
    }
    @Test public void in_sync_means_none() {
        assertEquals(NONE, ConfigReconcileDecision.decide(CAPABLE, "r1", "r1", false, null, NOW));
    }
    @Test public void drift_with_nothing_open_enqueues() {
        assertEquals(ENQUEUE, ConfigReconcileDecision.decide(CAPABLE, "r2", "r1", false, null, NOW));
        assertEquals(ENQUEUE, ConfigReconcileDecision.decide(CAPABLE, "r2", null, false, null, NOW));
    }
    @Test public void open_command_means_none() {
        assertEquals(NONE, ConfigReconcileDecision.decide(CAPABLE, "r2", "r1", true, null, NOW));
    }
    @Test public void recent_terminal_attempt_for_same_revision_backs_off() {
        AgentCommand recent = cmd("r2", "failed", NOW - 5 * 60 * 1000L);
        assertEquals(NONE, ConfigReconcileDecision.decide(CAPABLE, "r2", "r1", false, recent, NOW));
    }
    @Test public void old_terminal_attempt_retries() {
        AgentCommand old = cmd("r2", "failed", NOW - 2 * ConfigReconcileDecision.RETRY_BACKOFF_MS);
        assertEquals(ENQUEUE, ConfigReconcileDecision.decide(CAPABLE, "r2", "r1", false, old, NOW));
    }
    @Test public void terminal_attempt_for_a_different_revision_does_not_block() {
        AgentCommand other = cmd("r1", "done", NOW - 1000L);
        assertEquals(ENQUEUE, ConfigReconcileDecision.decide(CAPABLE, "r2", "r1", false, other, NOW));
    }
    @Test public void revisionOf_is_null_safe() {
        assertNull(ConfigReconcileDecision.revisionOf(null));
        AgentCommand junk = new AgentCommand(); junk.setPayload("not json");
        assertNull(ConfigReconcileDecision.revisionOf(junk));
        assertEquals("r9", ConfigReconcileDecision.revisionOf(cmd("r9", "done", 1L)));
    }
}
