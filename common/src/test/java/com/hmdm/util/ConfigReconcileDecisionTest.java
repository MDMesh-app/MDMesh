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
    @Test public void agent_change_resends_past_the_old_agents_done_but_not_past_a_failure() {
        AgentCommand justDone = cmd("r1", "done", NOW - 1000L);
        assertEquals(ENQUEUE, ConfigReconcileDecision.decide(CAPABLE, "r1", "r1", false, justDone, NOW, true));
        assertEquals(NONE, ConfigReconcileDecision.decide(CAPABLE, "r1", "r1", false, justDone, NOW, false));
        AgentCommand justFailed = cmd("r1", "failed", NOW - 1000L);
        assertEquals("a failed re-send keeps the normal backoff", NONE,
                ConfigReconcileDecision.decide(CAPABLE, "r1", "r1", false, justFailed, NOW, true));
        AgentCommand oldFailed = cmd("r1", "failed", NOW - 2 * ConfigReconcileDecision.RETRY_BACKOFF_MS);
        assertEquals(ENQUEUE, ConfigReconcileDecision.decide(CAPABLE, "r1", "r1", false, oldFailed, NOW, true));
        assertEquals("never stacks on an open command", NONE,
                ConfigReconcileDecision.decide(CAPABLE, "r1", "r1", true, null, NOW, true));
        assertEquals("still capability-gated", NONE,
                ConfigReconcileDecision.decide(Collections.<String>emptySet(), "r1", "r1", false, null, NOW, true));
    }
    private static AgentCommand abandoned(String revision, long at) {
        AgentCommand c = cmd(revision, "expired", at);
        c.setDetail(ConfigReconcileDecision.ABANDONED_DETAIL);
        return c;
    }
    @Test public void an_abandoned_attempt_is_sent_again_at_once() {
        // The agent checked in again without its result: it restarted mid-apply or never got the response.
        AgentCommand gone = abandoned("r2", NOW - 1000L);
        assertEquals(ENQUEUE, ConfigReconcileDecision.decide(CAPABLE, "r2", "r1", false, gone, null, NOW, false));
        assertEquals("an older failure of the same revision does not hold it back", ENQUEUE,
                ConfigReconcileDecision.decide(CAPABLE, "r2", "r1", false, gone,
                        cmd("r2", "failed", NOW - 2 * ConfigReconcileDecision.RETRY_BACKOFF_MS), NOW, false));
        assertEquals("after an agent update too", ENQUEUE,
                ConfigReconcileDecision.decide(CAPABLE, "r1", "r1", false, abandoned("r1", NOW - 1000L), null, NOW, true));
    }
    @Test public void a_second_abandoned_attempt_in_a_row_keeps_the_backoff() {
        // E.g. a document that kills the agent every time: one quick retry, then the normal backoff.
        AgentCommand again = abandoned("r2", NOW - 1000L);
        AgentCommand first = abandoned("r2", NOW - 60_000L);
        assertEquals(NONE, ConfigReconcileDecision.decide(CAPABLE, "r2", "r1", false, again, first, NOW, false));
        AgentCommand old = abandoned("r2", NOW - 2 * ConfigReconcileDecision.RETRY_BACKOFF_MS);
        assertEquals(ENQUEUE, ConfigReconcileDecision.decide(CAPABLE, "r2", "r1", false, old, first, NOW, false));
        assertEquals("an abandoned attempt of another revision before it does not count", ENQUEUE,
                ConfigReconcileDecision.decide(CAPABLE, "r2", "r1", false, again, abandoned("r1", NOW - 60_000L),
                        NOW, false));
    }
    @Test public void a_command_expired_by_its_lease_keeps_the_backoff() {
        AgentCommand leased = cmd("r2", "expired", NOW - 1000L);
        assertEquals(NONE, ConfigReconcileDecision.decide(CAPABLE, "r2", "r1", false, leased, null, NOW, false));
    }
    @Test public void abandoned_needs_the_marker_and_the_expired_status() {
        assertTrue(ConfigReconcileDecision.abandoned(abandoned("r1", 1L)));
        assertFalse(ConfigReconcileDecision.abandoned(cmd("r1", "expired", 1L)));
        AgentCommand lateResult = abandoned("r1", 1L);
        lateResult.setStatus("done"); // the device's late result overwrote the server's guess
        assertFalse(ConfigReconcileDecision.abandoned(lateResult));
        assertFalse(ConfigReconcileDecision.abandoned(null));
    }
    @Test public void agentChanged_needs_both_versions() {
        assertTrue(ConfigReconcileDecision.agentChanged("0.3.3", "0.3.2"));
        assertFalse(ConfigReconcileDecision.agentChanged("0.3.3", "0.3.3"));
        assertFalse("no baseline is not a change", ConfigReconcileDecision.agentChanged("0.3.3", null));
        assertFalse("unknown current version is not a change", ConfigReconcileDecision.agentChanged(null, "0.3.2"));
    }
    @Test public void revisionOf_is_null_safe() {
        assertNull(ConfigReconcileDecision.revisionOf(null));
        AgentCommand junk = new AgentCommand(); junk.setPayload("not json");
        assertNull(ConfigReconcileDecision.revisionOf(junk));
        assertEquals("r9", ConfigReconcileDecision.revisionOf(cmd("r9", "done", 1L)));
    }
}
