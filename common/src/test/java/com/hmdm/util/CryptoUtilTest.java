/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Covers the SHA-256 + constant-time-compare helpers backing the agent per-device secret. */
public class CryptoUtilTest {

    // Known-answer test: SHA-256("abc"), upper-cased as getSHA256String returns.
    private static final String SHA256_ABC =
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD";

    @Test
    public void sha256_isDeterministicAndCorrect() {
        assertEquals(SHA256_ABC, CryptoUtil.getSHA256String("abc"));
        assertEquals(CryptoUtil.getSHA256String("abc"), CryptoUtil.getSHA256String("abc"));
    }

    @Test
    public void sha256_differsForDifferentInput() {
        assertFalse(CryptoUtil.getSHA256String("abc").equals(CryptoUtil.getSHA256String("abd")));
    }

    @Test
    public void constantTimeEquals_matchesOnlyIdentical() {
        assertTrue(CryptoUtil.constantTimeEquals(SHA256_ABC, SHA256_ABC));
        assertFalse(CryptoUtil.constantTimeEquals(SHA256_ABC, "DEADBEEF"));
        assertFalse(CryptoUtil.constantTimeEquals(null, SHA256_ABC));
        assertFalse(CryptoUtil.constantTimeEquals(SHA256_ABC, null));
    }

    @Test
    public void logFingerprint_isShortStableAndHidesTheSecret() {
        // SHA-256("abc") starts ba7816bf
        assertEquals("sha256:ba7816bf", CryptoUtil.logFingerprint("abc"));
        assertEquals(CryptoUtil.logFingerprint("qr-key-1"), CryptoUtil.logFingerprint("qr-key-1"));
        assertFalse(CryptoUtil.logFingerprint("qr-key-1").contains("qr-key-1"));
        assertFalse(CryptoUtil.logFingerprint("qr-key-1").equals(CryptoUtil.logFingerprint("qr-key-2")));
        assertEquals("null", CryptoUtil.logFingerprint(null));
    }

    @Test
    public void deviceCreateOptionsNeverPrintTheConfigurationKey() {
        com.hmdm.rest.json.DeviceCreateOptions options = new com.hmdm.rest.json.DeviceCreateOptions();
        options.setConfiguration("qr-key-1");
        assertFalse(options.toString(), options.toString().contains("qr-key-1"));
        assertTrue(options.toString(), options.toString().contains(CryptoUtil.logFingerprint("qr-key-1")));
    }
}
