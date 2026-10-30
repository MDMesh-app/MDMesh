package com.hmdm.util;

import com.hmdm.persistence.domain.AgentCommand;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static com.hmdm.util.AppReconcileDecision.State.*;
import static org.junit.Assert.*;

public class AppReconcileDecisionTest {
    private static final long NOW = 1_000_000_000L;
    private static final long MIN = 60_000L;

    private static AgentCommand install(String pkg, int versionCode, String status, Long deliveredAt, Long completedAt) {
        AgentCommand c = new AgentCommand();
        c.setType("app.install"); c.setStatus(status); c.setDeliveredAt(deliveredAt); c.setCompletedAt(completedAt);
        c.setPayload("{\"url\":\"https://x/a.apk\",\"packageName\":\"" + pkg + "\""
                + (versionCode > 0 ? ",\"versionCode\":" + versionCode : "") + ",\"sha256\":\"" + "ab".repeat(32) + "\"}");
        return c;
    }

    private static AgentCommand uninstall(String pkg, String status) {
        AgentCommand c = new AgentCommand();
        c.setType("app.uninstall"); c.setStatus(status);
        c.setPayload("{\"packageName\":\"" + pkg + "\"}");
        return c;
    }

    /** Newest first, as the history query returns them. */
    private static List<AgentCommand> history(AgentCommand... newestFirst) {
        return new ArrayList<>(Arrays.asList(newestFirst));
    }

    private static AppReconcileDecision.State decide(int versionCode, List<AgentCommand> history) {
        return AppReconcileDecision.decide("com.a", versionCode, history, NOW);
    }

    @Test public void a_new_app_is_queued() {
        assertEquals(QUEUE, decide(4, Collections.<AgentCommand>emptyList()));
        assertEquals(QUEUE, decide(4, history(install("com.other", 4, "done", NOW, NOW))));
    }
    @Test public void an_installed_app_is_left_alone() {
        assertEquals(INSTALLED, decide(4, history(install("com.a", 4, "done", NOW - MIN, NOW))));
        // A newer version already there satisfies it (the agent would skip the older one).
        assertEquals(INSTALLED, decide(4, history(install("com.a", 5, "done", NOW - MIN, NOW))));
        // Any version done counts when the configuration's version code is unknown.
        assertEquals(INSTALLED, decide(0, history(install("com.a", 3, "done", NOW - MIN, NOW))));
        // A later failed re-send does not make an installed app missing.
        assertEquals(INSTALLED, decide(4, history(install("com.a", 4, "failed", NOW - MIN, NOW),
                install("com.a", 4, "done", NOW - 9 * MIN, NOW - 8 * MIN))));
    }
    @Test public void a_version_change_installs_the_new_version() {
        assertEquals(QUEUE, decide(5, history(install("com.a", 4, "done", NOW - MIN, NOW))));
        // A failure of another version is not an attempt at this one.
        assertEquals(QUEUE, decide(5, history(install("com.a", 4, "failed", NOW - MIN, NOW))));
    }
    @Test public void an_install_in_flight_is_waited_for() {
        assertEquals(IN_FLIGHT, decide(4, history(install("com.a", 4, "pending", null, null))));
        assertEquals(IN_FLIGHT, decide(4, history(install("com.a", 4, "delivered", NOW, null))));
    }
    @Test public void a_failed_install_backs_off_then_retries() {
        assertEquals(BACK_OFF, decide(4, history(install("com.a", 4, "failed", NOW - 2 * MIN, NOW - MIN))));
        long first = AppReconcileDecision.FIRST_RETRY_MS;
        assertEquals(QUEUE, decide(4, history(install("com.a", 4, "failed", NOW - first - 2 * MIN, NOW - first))));
        // A lost result (expired after delivery) counts the same.
        assertEquals(BACK_OFF, decide(4, history(install("com.a", 4, "expired", NOW - 2 * MIN, NOW - MIN))));
    }
    @Test public void the_backoff_grows_and_the_retries_stop() {
        assertEquals(15 * MIN, AppReconcileDecision.backoffMs(1));
        assertEquals(60 * MIN, AppReconcileDecision.backoffMs(2));
        AgentCommand second = install("com.a", 4, "failed", NOW - 31 * MIN, NOW - 30 * MIN);
        AgentCommand first = install("com.a", 4, "failed", NOW - 99 * MIN, NOW - 98 * MIN);
        assertEquals(BACK_OFF, decide(4, history(second, first)));
        AgentCommand third = install("com.a", 4, "failed", NOW - 3 * MIN, NOW - 2 * MIN);
        assertEquals(GAVE_UP, decide(4, history(third, second, first)));
        // Hours later still: no hot loop on an install that keeps failing.
        assertEquals(GAVE_UP, AppReconcileDecision.decide("com.a", 4, history(third, second, first), NOW + 48 * 60 * MIN));
    }
    @Test public void an_install_the_server_refused_is_recorded_once() {
        // No SHA-256 for the APK: recorded failed without ever being delivered.
        assertEquals(GAVE_UP, decide(4, history(install("com.a", 4, "failed", null, NOW - 99 * MIN))));
    }
    @Test public void an_install_that_expired_before_reaching_the_device_is_sent_again() {
        assertEquals(QUEUE, decide(4, history(install("com.a", 4, "expired", null, NOW - MIN))));
    }
    @Test public void an_agent_that_cannot_install_is_not_asked_again() {
        assertEquals(GAVE_UP, decide(4, history(install("com.a", 4, "unsupported", NOW - MIN, NOW))));
    }
    @Test public void an_app_uninstalled_after_its_install_is_installed_again() {
        assertEquals(QUEUE, decide(4, history(uninstall("com.a", "done"), install("com.a", 4, "done", NOW - MIN, NOW))));
        // An uninstall that did not happen leaves it installed.
        assertEquals(INSTALLED, decide(4, history(uninstall("com.a", "failed"), install("com.a", 4, "done", NOW, NOW))));
        // Attempts before the uninstall don't count against the reinstall.
        assertEquals(QUEUE, decide(4, history(uninstall("com.a", "done"),
                install("com.a", 4, "failed", NOW - 3 * MIN, NOW - 2 * MIN))));
    }
    @Test public void unreadable_payloads_are_ignored() {
        AgentCommand junk = new AgentCommand();
        junk.setType("app.install"); junk.setStatus("done"); junk.setPayload("not json");
        assertEquals(QUEUE, decide(4, history(junk, null)));
    }
    @Test public void only_installed_and_given_up_are_settled() {
        assertTrue(AppReconcileDecision.settled(INSTALLED));
        assertTrue(AppReconcileDecision.settled(GAVE_UP));
        assertFalse(AppReconcileDecision.settled(QUEUE));
        assertFalse(AppReconcileDecision.settled(IN_FLIGHT));
        assertFalse(AppReconcileDecision.settled(BACK_OFF));
    }
    @Test public void the_revision_is_the_configuration_and_its_install_set_in_any_order() {
        String r = AppReconcileDecision.revision(5, Arrays.asList(30, 1, 2));
        assertEquals("5:1,2,30", r);   // numeric order, as the changeSet's string_agg(... ORDER BY v) on ints
        assertEquals(r, AppReconcileDecision.revision(5, Arrays.asList(2, 30, 1, 1)));
        assertEquals("5:", AppReconcileDecision.revision(5, Collections.<Integer>emptyList()));
    }
    @Test public void a_stored_revision_lists_its_configuration_and_version_ids() {
        assertEquals(Integer.valueOf(5), AppReconcileDecision.configurationId("5:1,2,30"));
        assertEquals(new HashSet<>(Arrays.asList(1, 2, 30)), AppReconcileDecision.versionIds("5:1,2,30"));
        assertEquals(new HashSet<>(Collections.singletonList(7)), AppReconcileDecision.versionIds("5: 7 ,x,"));
        assertTrue(AppReconcileDecision.versionIds("5:").isEmpty());
        assertTrue(AppReconcileDecision.versionIds(null).isEmpty());
        assertNull(AppReconcileDecision.configurationId(null));
        assertNull(AppReconcileDecision.configurationId("1,2"));
    }
    @Test public void an_accepted_install_is_in_flight_and_a_stale_one_counts_as_an_attempt() {
        assertEquals(IN_FLIGHT, decide(4, history(install("com.a", 4, "accepted", NOW - MIN, null))));
        // Accepted and never finished, twice, then failed: three attempts, so the retries stop.
        assertEquals(GAVE_UP, decide(4, history(install("com.a", 4, "failed", NOW - 3 * MIN, NOW - 2 * MIN),
                install("com.a", 4, "accepted", NOW - 90 * MIN, null),
                install("com.a", 4, "delivered", NOW - 190 * MIN, null))));
    }
    @Test public void failures_from_long_ago_dont_count_when_the_app_is_added_again() {
        long old = NOW - AppReconcileDecision.ATTEMPT_WINDOW_MS - 60 * MIN;
        List<AgentCommand> h = history(at(install("com.a", 4, "failed", old + 30 * MIN, old + 31 * MIN), old + 29 * MIN),
                at(install("com.a", 4, "failed", old + 10 * MIN, old + 11 * MIN), old + 9 * MIN),
                at(install("com.a", 4, "failed", old + MIN, old + 2 * MIN), old));
        assertEquals(QUEUE, decide(4, h));
        // The same three inside the window: given up.
        assertEquals(GAVE_UP, AppReconcileDecision.decide("com.a", 4, h, old + 40 * MIN));
        // An old server refusal is tried again too (a SHA-256 may have been added since).
        assertEquals(QUEUE, decide(4, history(at(install("com.a", 4, "failed", null, old), old))));
        // An install done long ago still counts as installed.
        assertEquals(INSTALLED, decide(4, history(at(install("com.a", 4, "done", old, old), old))));
    }
    @Test public void the_history_is_read_once_for_every_app() {
        AppReconcileDecision.History h = AppReconcileDecision.History.of(history(install("com.b", 1, "done", NOW, NOW),
                install("com.a", 4, "pending", null, null)));
        assertEquals(IN_FLIGHT, AppReconcileDecision.decide("com.a", 4, h, NOW));
        assertEquals(INSTALLED, AppReconcileDecision.decide("com.b", 1, h, NOW));
        assertEquals(QUEUE, AppReconcileDecision.decide("com.c", 1, h, NOW));
    }

    private static AgentCommand at(AgentCommand c, long createdAt) {
        c.setCreatedAt(createdAt);
        return c;
    }
}
