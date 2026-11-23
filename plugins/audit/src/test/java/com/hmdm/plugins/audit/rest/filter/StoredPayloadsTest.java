/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The scrub policy for old records, without a database (AuditLogScrubTest runs it on PostgreSQL). */
public class StoredPayloadsTest {

    private static final String HEAD = "Method: PUT\nURI: /rest/private/users";
    private static final String UA = "\nUser-Agent: Mozilla/5.0";

    @Test
    public void anOldBodyOverOneMegabyteIsReplacedUnparsed() {
        StringBuilder body = new StringBuilder("{\"password\":\"big-1\",\"pad\":\"");
        while (body.length() <= ServletRequestAuditWrapper.MAX_BODY) {
            body.append("xxxxxxxxxxxxxxxx");
        }
        body.append("\"}");
        assertEquals(HEAD + "\nBody: (over 1 MB, not stored)" + UA,
                StoredPayloads.scrub("plugin.audit.action.update.user", HEAD + "\nBody: " + body + UA));
    }

    @Test
    public void anOldBodyOverOneMegabyteInUtf8IsReplacedToo() {
        // Under the limit in characters, over it in bytes
        StringBuilder body = new StringBuilder("{\"note\":\"");
        while (body.length() <= ServletRequestAuditWrapper.MAX_BODY / 2) {
            body.append("€€€€€€€€");             // 3 bytes each in UTF-8
        }
        body.append("\"}");
        assertEquals(HEAD + "\nBody: (over 1 MB, not stored)" + UA,
                StoredPayloads.scrub("plugin.audit.action.update.user", HEAD + "\nBody: " + body + UA));
    }

    @Test
    public void aNoteThisReleaseWroteIsKept() {
        for (String note : new String[]{"(over 1 MB, not stored)", "(not valid JSON, not stored)"}) {
            String payload = HEAD + "\nBody: " + note + UA;
            assertEquals(payload, StoredPayloads.scrub("plugin.audit.action.update.user", payload));
        }
    }

    @Test
    public void aPayloadTooLargeToReadKeepsItsHeaderAndUserAgent() {
        assertEquals(HEAD + "\nBody: (over 1 MB, not stored)" + UA,
                StoredPayloads.scrubTooLarge(HEAD + "\nBody: {\"password\":\"x", "xxxxx\"}" + UA));
        assertEquals(HEAD + "\nBody: (over 1 MB, not stored)",
                StoredPayloads.scrubTooLarge(HEAD + "\nBody: xxx", "xxxx"));
    }

    @Test
    public void aPayloadTooLargeToReadWithNoBodyInItsHeadKeepsOnlyWhatIsSafe() {
        // Whatever made it large is not the body: nothing of it is kept but the method, a masked URI and a note
        assertEquals("Method: PUT\nURI: /rest/private/x?token=******\nBody: (over 1 MB, not stored)",
                StoredPayloads.scrubTooLarge("Method: PUT\nURI: /rest/private/x?token=t-1\nsomething: secret-2",
                        "secret-3"));
        StringBuilder longUri = new StringBuilder("Method: PUT\nURI: /");
        while (longUri.length() < 4096) {
            longUri.append("segment-secret-4/");
        }
        String scrubbed = StoredPayloads.scrubTooLarge(longUri.substring(0, 4096), "x");
        assertTrue(scrubbed, scrubbed.length() < 600);
        assertTrue(scrubbed, scrubbed.endsWith("\nBody: (over 1 MB, not stored)"));
        assertEquals("\nBody: (over 1 MB, not stored)", StoredPayloads.scrubTooLarge("x".repeat(4096), "y"));
    }

    @Test
    public void aPayloadTooLargeToReadKeepsAUserAgentCutShort() {
        String agent = "u".repeat(2000);
        String scrubbed = StoredPayloads.scrubTooLarge(HEAD + "\nBody: {", "xx\nUser-Agent: " + agent);
        assertEquals(HEAD + "\nBody: (over 1 MB, not stored)\nUser-Agent: " + agent.substring(0, 512), scrubbed);
    }

    @Test
    public void aBodyUnderTheLimitIsMaskedAsANewOneWouldBe() {
        assertEquals(HEAD + "\nBody: {\"login\":\"rbk\",\"password\":\"******\"}" + UA,
                StoredPayloads.scrub("plugin.audit.action.update.user",
                        HEAD + "\nBody: { \"login\": \"rbk\", \"password\": \"pw-1\" }" + UA));
    }
}
