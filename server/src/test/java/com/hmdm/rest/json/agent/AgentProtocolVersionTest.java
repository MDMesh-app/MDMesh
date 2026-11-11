package com.hmdm.rest.json.agent;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The protocol version the server advertises is the highest row of proto/VERSIONING.md (compared as numbers). */
public class AgentProtocolVersionTest {

    /**
     * Rows documented on this branch whose constant bump waits for the merge order to settle (#188 sets 1.9 first,
     * then this branch sets 1.10). Empty this, and set AgentProtocol.VERSION to the highest row, when they merge.
     */
    private static final Set<String> AWAITING_BUMP = new HashSet<>(Arrays.asList("1.10"));

    @Test
    public void theConstantIsTheHighestDocumentedVersion() throws Exception {
        Path doc = Paths.get("..", "proto", "VERSIONING.md");
        if (!Files.exists(doc)) doc = Paths.get("proto", "VERSIONING.md");
        Matcher m = Pattern.compile("(?m)^\\| (\\d+)\\.(\\d+) \\|").matcher(new String(Files.readAllBytes(doc), StandardCharsets.UTF_8));
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
        assertEquals(highest[0] + "." + highest[1], AgentProtocol.VERSION);
        // Numeric, not string, order: "1.10" is above "1.9" and "1.7".
        if (highestPending != null) assertTrue(compare(highestPending, highest) > 0);
    }

    private static int compare(int[] a, int[] b) {
        return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
    }
}
