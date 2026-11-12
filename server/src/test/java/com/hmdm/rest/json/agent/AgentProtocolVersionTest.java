package com.hmdm.rest.json.agent;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The protocol version the server advertises is the highest row of proto/VERSIONING.md (compared as numbers). */
public class AgentProtocolVersionTest {

    /**
     * Rows documented on this branch whose constant bump waits for the merge order to settle (#188 sets 1.9 first,
     * then this branch sets 1.10). Empty this, and set AgentProtocol.VERSION to the highest row, when they merge.
     */
    private static final Set<String> AWAITING_BUMP = new HashSet<>(Arrays.asList("1.10"));

    /**
     * Versions in AWAITING_BUMP whose bump is due (the row below them is documented) but deliberately held, each with
     * why. While held the test passes and prints the due message; it can't be held without a reason, and a held
     * version that is already the protocol version fails (empty both sets then). A release clears every hold first
     * (RELEASING.md, step 2).
     */
    private static final Map<String, String> BUMP_HELD = Collections.singletonMap("1.10",
            "1.9 is on main (#188); the merge order of feat/kiosk-background-console and feat/agent-ui-p4-p5 decides "
                    + "which of them sets AgentProtocol.VERSION to 1.10");

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
        // An exception may only wait for the row just below it: once that row is documented (#188's 1.9 merged before
        // this branch's 1.10), the bump is due, and so is emptying AWAITING_BUMP.
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

    private static int compare(int[] a, int[] b) {
        return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
    }
}
