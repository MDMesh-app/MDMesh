/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.util;

import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.RolloutDeviceRow;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Unit tests for the pure rollout-progress classifier (no DB). */
public class RolloutProgressTest {

    /** appManagement:["silentInstall"] flattens to the gate token app.silentInstall. */
    private static final String CAPS_OK = "{\"appManagement\":[\"silentInstall\"]}";
    /** No app-management capability ⇒ ineligible (the delivery gate would block the install). */
    private static final String CAPS_NONE = "{\"policy\":[\"wifi\"]}";

    private static RolloutDeviceRow row(String num, String version, String caps) {
        RolloutDeviceRow r = new RolloutDeviceRow();
        r.setDeviceNumber(num);
        r.setAgentVersion(version);
        r.setCapabilitiesJson(caps);
        return r;
    }

    @Test
    public void classifiesEachState() {
        assertEquals(RolloutProgress.Status.UPDATED,
                RolloutProgress.classify("1.2.0", row("a", "1.2.0", CAPS_OK), false));
        assertEquals(RolloutProgress.Status.INELIGIBLE,
                RolloutProgress.classify("1.2.0", row("d", "1.1.0", CAPS_NONE), false));
        assertEquals(RolloutProgress.Status.PENDING,
                RolloutProgress.classify("1.2.0", row("b", "1.1.0", CAPS_OK), true));
        assertEquals(RolloutProgress.Status.OUTSTANDING,
                RolloutProgress.classify("1.2.0", row("c", "1.1.0", CAPS_OK), false));
    }

    @Test
    public void updatedBeatsIneligible() {
        // A device already on target is UPDATED even if it no longer advertises the capability.
        assertEquals(RolloutProgress.Status.UPDATED,
                RolloutProgress.classify("1.2.0", row("a", "1.2.0", CAPS_NONE), false));
    }

    @Test
    public void aDeviceOnANewerAgentIsUpdatedAndNeverOutstanding() {
        // Queuing it the target would be a downgrade the agent refuses, and the rollout would never complete.
        assertEquals(RolloutProgress.Status.UPDATED,
                RolloutProgress.classify("1.2.0", row("a", "1.3.0", CAPS_OK), false));
        assertEquals(RolloutProgress.Status.UPDATED,
                RolloutProgress.classify("1.2.0", row("a", "1.2.1", CAPS_OK), true));
        assertEquals(RolloutProgress.Status.UPDATED,
                RolloutProgress.classify("0.9.0", row("a", "0.10.0", CAPS_OK), false)); // numeric, not text
        assertEquals(RolloutProgress.Status.UPDATED,
                RolloutProgress.classify("1.2.0", row("a", "2.0.0", CAPS_NONE), false));
        assertEquals(RolloutProgress.Status.OUTSTANDING,
                RolloutProgress.classify("0.10.0", row("a", "0.9.0", CAPS_OK), false));
        assertEquals(RolloutProgress.Status.OUTSTANDING,
                RolloutProgress.classify("1.2.0", row("a", "1.1.9", CAPS_OK), false));
    }

    @Test
    public void comparesVersionsTheWayReleasesAreNumbered() {
        assertTrue(RolloutProgress.atOrAbove("1.2.0", "1.2.0"));
        assertTrue(RolloutProgress.atOrAbove("1.2", "1.2.0"));          // a missing part is 0
        assertTrue(RolloutProgress.atOrAbove("v1.2.0", "1.2.0"));
        assertTrue(RolloutProgress.atOrAbove(" 1.2.0 ", "1.2.0"));
        assertTrue(RolloutProgress.atOrAbove("1.2.0+42", "1.2.0"));     // build metadata doesn't count
        assertTrue(RolloutProgress.atOrAbove("1.3.0-dev", "1.2.0"));
        assertFalse(RolloutProgress.atOrAbove("1.2.0-rc1", "1.2.0"));   // a pre-release is below its release
        assertTrue(RolloutProgress.atOrAbove("1.2.0-rc1", "1.2.0-rc1"));
        assertFalse(RolloutProgress.atOrAbove("1.1.99", "1.2.0"));
    }

    @Test
    public void anUnreadableOrMissingVersionIsNeverAtOrAboveTheTarget() {
        // Unknown means "not shown to be current": the device stays in the rollout, as before, rather than being
        // counted done on a guess.
        assertFalse(RolloutProgress.atOrAbove(null, "1.2.0"));
        assertFalse(RolloutProgress.atOrAbove("", "1.2.0"));
        assertFalse(RolloutProgress.atOrAbove("   ", "1.2.0"));
        assertFalse(RolloutProgress.atOrAbove("unknown", "1.2.0"));
        assertFalse(RolloutProgress.atOrAbove("1..2", "1.2.0"));
        assertFalse(RolloutProgress.atOrAbove("99999999999999999999.0", "1.2.0")); // no overflow into "newer"
        assertFalse(RolloutProgress.atOrAbove("1.2.0", null));
        assertFalse(RolloutProgress.atOrAbove("1.2.0", ""));
        // A target that isn't a version still matches itself exactly, and nothing else.
        assertTrue(RolloutProgress.atOrAbove("nightly", "nightly"));
        assertFalse(RolloutProgress.atOrAbove("9.9.9", "nightly"));
        assertEquals(RolloutProgress.Status.OUTSTANDING,
                RolloutProgress.classify("1.2.0", row("a", "garbage", CAPS_OK), false));
        assertEquals(RolloutProgress.Status.OUTSTANDING,
                RolloutProgress.classify("1.2.0", row("a", null, CAPS_OK), false));
    }

    @Test
    public void aCohortOnNewerAgentsIsComplete() {
        RolloutProgress.Counts c = RolloutProgress.counts("1.2.0", Arrays.asList(
                row("a", "1.2.0", CAPS_OK),
                row("b", "1.3.0", CAPS_OK)), new HashSet<>(Collections.singletonList("b")));
        assertEquals(2, c.getUpdated());
        assertEquals(0, c.getPending());
        assertTrue(RolloutProgress.isComplete(c));
    }

    @Test
    public void countsAddUp() {
        List<RolloutDeviceRow> rows = Arrays.asList(
                row("a", "1.2.0", CAPS_OK),   // updated
                row("b", "1.1.0", CAPS_OK),   // pending
                row("c", "1.1.0", CAPS_OK),   // outstanding
                row("d", "1.1.0", CAPS_NONE)  // ineligible
        );
        Set<String> pending = new HashSet<>(Collections.singletonList("b"));
        RolloutProgress.Counts c = RolloutProgress.counts("1.2.0", rows, pending);
        assertEquals(4, c.getTotal());
        assertEquals(1, c.getUpdated());
        assertEquals(1, c.getPending());
        assertEquals(1, c.getOutstanding());
        assertEquals(1, c.getIneligible());
    }

    @Test
    public void emptyCohortIsZero() {
        RolloutProgress.Counts c = RolloutProgress.counts("1.2.0", Collections.emptyList(), null);
        assertEquals(0, c.getTotal());
        assertFalse(RolloutProgress.isComplete(c));
    }

    @Test
    public void completionRequiresEveryUpdateCapableDeviceToBeCurrent() {
        RolloutProgress.Counts current = RolloutProgress.counts("1.2.0", Arrays.asList(
                row("a", "1.2.0", CAPS_OK),
                row("b", "1.1.0", CAPS_NONE)), Collections.<String>emptySet());
        assertTrue(RolloutProgress.isComplete(current));

        RolloutProgress.Counts outstanding = RolloutProgress.counts("1.2.0", Arrays.asList(
                row("a", "1.2.0", CAPS_OK),
                row("b", "1.1.0", CAPS_OK)), Collections.<String>emptySet());
        assertFalse(RolloutProgress.isComplete(outstanding));
    }

    @Test
    public void aCohortWithNoDeviceThatCanUpdateItselfIsNotComplete() {
        RolloutProgress.Counts blocked = RolloutProgress.counts("1.2.0", Arrays.asList(
                row("a", "1.1.0", CAPS_NONE),
                row("b", "1.1.0", CAPS_NONE)), Collections.<String>emptySet());
        assertEquals(2, blocked.getIneligible());
        assertFalse(RolloutProgress.isComplete(blocked));
    }

    private static AgentCommand install(String num, String payload) {
        AgentCommand c = new AgentCommand();
        c.setDeviceNumber(num);
        c.setType("app.install");
        c.setPayload(payload);
        return c;
    }

    @Test
    public void inFlightCountsOnlyTheRolloutPackage() {
        // An unrelated install (an F-Droid app, a seeded app) must not mark a device as "installing" the update.
        List<AgentCommand> inFlight = Arrays.asList(
                install("a", "{\"url\":\"https://x/update/agent.apk?v=302\",\"packageName\":\"com.mdmesh.agent\"}"),
                install("b", "{\"url\":\"https://f-droid.org/repo/org.fdroid.fdroid_1.apk\",\"packageName\":\"org.fdroid.fdroid\"}"),
                install("c", "not json"),
                install("d", null));
        assertEquals(new HashSet<>(Collections.singletonList("a")),
                RolloutProgress.inFlightFor("com.mdmesh.agent", inFlight));
    }

    @Test
    public void requeuesWhenNothingIsInFlightOrItExpiredOrFailedAFewTimes() {
        assertTrue(RolloutProgress.shouldRequeue(null, false, 0));        // never queued (e.g. skipped at create)
        assertTrue(RolloutProgress.shouldRequeue("expired", true, 1));    // delivered, the result was lost
        assertTrue(RolloutProgress.shouldRequeue("expired", false, 7));   // never reached the device: always re-sent
        assertFalse(RolloutProgress.shouldRequeue("expired", true, 3));   // a device that installs but never reports
                                                                          // the target version is not reinstalled forever
        assertTrue(RolloutProgress.shouldRequeue("failed", true, 1));     // a transient download error is retried
        assertTrue(RolloutProgress.shouldRequeue("failed", true, 2));
        assertFalse(RolloutProgress.shouldRequeue("failed", true, 3));    // but not on every check-in forever
        assertFalse(RolloutProgress.shouldRequeue("pending", false, 1));
        assertFalse(RolloutProgress.shouldRequeue("delivered", true, 1));
        assertFalse(RolloutProgress.shouldRequeue("done", true, 1));      // installed; the new version reports on restart
        assertFalse(RolloutProgress.shouldRequeue("unsupported", true, 1));
    }
}
