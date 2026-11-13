package com.hmdm.rest.json.agent;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The protocol version the server advertises is the highest row of proto/VERSIONING.md (compared as numbers). */
public class AgentProtocolVersionTest {

    /**
     * Documented rows whose constant bump waits for another branch's merge (say two open PRs that each add a row): the
     * test then skips them when finding the highest row. Empty unless such a wait is in progress; empty it, and set
     * AgentProtocol.VERSION to the highest row, when they merge.
     */
    private static final Set<String> AWAITING_BUMP = Collections.emptySet();

    /**
     * Versions in AWAITING_BUMP whose bump is due (the row below them is documented) but deliberately held, each with
     * why. While held the test passes and prints the due message; it can't be held without a reason, and a held
     * version that is already the protocol version fails (empty both sets then). A release clears every hold first
     * (RELEASING.md, step 2).
     */
    private static final Map<String, String> BUMP_HELD = Collections.emptyMap();

    @Test
    public void theConstantIsTheHighestDocumentedVersion() throws Exception {
        Path doc = Paths.get("..", "proto", "VERSIONING.md");
        if (!Files.exists(doc)) doc = Paths.get("proto", "VERSIONING.md");
        Matcher m = Pattern.compile("(?m)^\\|\\s*(\\d+)\\.(\\d+)\\s*\\|").matcher(new String(Files.readAllBytes(doc), StandardCharsets.UTF_8));
        List<int[]> rows = new ArrayList<>();
        while (m.find()) rows.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
        assertTrue("rows found", rows.size() > 3);
        int[] highest = null;
        int[] highestPending = null;
        for (int[] r : rows) {
            boolean pending = AWAITING_BUMP.contains(r[0] + "." + r[1]);
            if (pending) {
                if (highestPending == null || compare(r, highestPending) > 0) highestPending = r;
            } else if (highest == null || compare(r, highest) > 0) {
                highest = r;
            }
        }
        // An exception may only wait for the row just below it: once that row is documented (a 1.9 merged before
        // a branch's 1.10), the bump is due, and so is emptying AWAITING_BUMP.
        assertTrue("every held bump awaits one", AWAITING_BUMP.containsAll(BUMP_HELD.keySet()));
        for (String pending : AWAITING_BUMP) {
            assertFalse(pending + " is the protocol version already: empty AWAITING_BUMP and BUMP_HELD",
                    pending.equals(AgentProtocol.VERSION));
            String[] p = pending.split("\\.");
            int[] below = {Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1};
            boolean due = rows.stream().anyMatch(r -> compare(r, below) == 0);
            String message = pending + " can be the protocol version now (" + below[0] + "." + below[1]
                    + " is documented): set AgentProtocol.VERSION to " + pending + " and empty AWAITING_BUMP";
            String held = BUMP_HELD.get(pending);
            if (due && held != null) {
                assertFalse("a held bump needs its reason", held.trim().isEmpty());
                System.err.println("AgentProtocolVersionTest: " + message + ". Held: " + held + ". Clear the hold "
                        + "before a release (RELEASING.md, step 2).");
            } else {
                assertFalse(message, due);
            }
        }
        assertEquals(highest[0] + "." + highest[1], AgentProtocol.VERSION);
        // Numeric, not string, order: "1.10" is above "1.9" and "1.7".
        if (highestPending != null) assertTrue(compare(highestPending, highest) > 0);
    }

    /**
     * A release never ships with a bump held: the CHANGELOG line announcing a held version must still be under
     * Unreleased. The release PR moves Unreleased into its version's section (RELEASING.md, step 2), so this fails on
     * that PR, before anything is tagged, until the hold is cleared.
     */
    @Test
    public void aHeldBumpIsStillUnreleased() throws Exception {
        Path changelog = Paths.get("..", "CHANGELOG.md");
        if (!Files.exists(changelog)) changelog = Paths.get("CHANGELOG.md");
        String text = new String(Files.readAllBytes(changelog), StandardCharsets.UTF_8);
        for (String held : BUMP_HELD.keySet()) {
            String problem = heldBumpProblem(text, held);
            assertNull(problem, problem);
        }
    }

    @Test
    public void aHeldBumpInAReleasedSectionIsCaught() {
        String unreleased = "# Changelog\n\n## Unreleased\n\nUnder the hood\n- Protocol 1.10: `fit` (additive).\n\n"
                + "## v0.4.0 — 2026-11-03\n\n- Agent protocol 1.8\n";
        assertNull(heldBumpProblem(unreleased, "1.10"));
        // The release PR: Unreleased moved to ## v0.5.0, with the hold still there.
        String releasing = "# Changelog\n\n## Unreleased\n\n## v0.5.0 — 2026-11-24\n\nUnder the hood\n"
                + "- Protocol 1.10: `fit` (additive).\n\n## v0.4.0 — 2026-11-03\n";
        assertNotNull(heldBumpProblem(releasing, "1.10"));
        assertNotNull("no Unreleased section", heldBumpProblem("# Changelog\n\n## v0.5.0\n- Protocol 1.10\n", "1.10"));
        assertNotNull("another version is not this one",
                heldBumpProblem("## Unreleased\n- Protocol 1.100 and protocol 11.10\n", "1.10"));
        assertNull("agent protocol 1.10 counts", heldBumpProblem("## Unreleased\n- Agent protocol 1.10 (additive)\n", "1.10"));
    }

    /** Why a held version isn't announced under Unreleased in {@code changelog}; null when it is. */
    static String heldBumpProblem(String changelog, String version) {
        Matcher unreleased = Pattern.compile("(?m)^## Unreleased\\s*$").matcher(changelog);
        if (!unreleased.find()) return "CHANGELOG.md has no \"## Unreleased\" section to announce held protocol " + version;
        Matcher next = Pattern.compile("(?m)^## ").matcher(changelog);
        int end = next.find(unreleased.end()) ? next.start() : changelog.length();
        String section = changelog.substring(unreleased.end(), end);
        boolean announced = Pattern.compile("(?i)\\bprotocol " + Pattern.quote(version) + "(?![0-9])").matcher(section).find();
        return announced ? null : "Agent protocol " + version + " is held (BUMP_HELD) but not announced under ## Unreleased "
                + "in CHANGELOG.md: a release section has it, so clear the hold first (RELEASING.md, step 2: empty "
                + "AWAITING_BUMP and BUMP_HELD, set AgentProtocol.VERSION), or announce it under Unreleased";
    }

    private static int compare(int[] a, int[] b) {
        return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
    }
}
