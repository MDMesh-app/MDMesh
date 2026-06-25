/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
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
}
