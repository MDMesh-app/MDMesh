/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The kiosk exit PIN rule: 6 to 12 digits or none for a new PIN; a stored PIN from before the rule is kept. */
public class KioskExitPinTest {

    @Test
    public void sixToTwelveDigitsOrNoneFollowTheRule() {
        for (String ok : new String[]{null, "", "482139", "000000", "123456789012", "12345678"}) {
            assertTrue(String.valueOf(ok), KioskExitPin.valid(ok));
        }
    }

    @Test
    public void anythingElseDoesNot() {
        String[] bad = {"12345", "1234567890123", "1234", "48213a", "482 139", " 482139", "482139 ", "４８２１３９",
                "١٢٣٤٥٦", "+482139", "-482139", "48.2139", "letmeout", " ", "\t", "482139\n", "0x1234"};
        for (String pin : bad) {
            assertFalse(pin, KioskExitPin.valid(pin));
            assertTrue(pin, KioskExitPin.legacy(pin));
        }
    }

    @Test
    public void aNewConfigurationMustFollowTheRule() {
        assertEquals(KioskExitPin.FORMAT_MESSAGE, KioskExitPin.problem("1234", null));
        assertNull(KioskExitPin.problem("482139", null));
        assertNull(KioskExitPin.problem(null, null));
        assertNull(KioskExitPin.problem("", null));
    }

    @Test
    public void aLegacyPinSentBackUnchangedIsKept() {
        assertNull(KioskExitPin.problem("letmeout", "letmeout"));
        assertNull(KioskExitPin.problem("1234", "1234"));
    }

    @Test
    public void aKioskEnterPinFollowsTheRuleWithNoExemption() {
        assertNull(KioskExitPin.enterCommandProblem("kiosk.enter", "{\"password\":\"482139\"}"));
        assertNull("no PIN: no exit on the device", KioskExitPin.enterCommandProblem("kiosk.enter", "{\"password\":\"\"}"));
        assertNull(KioskExitPin.enterCommandProblem("kiosk.enter", "{\"password\":null}"));
        assertNull(KioskExitPin.enterCommandProblem("kiosk.enter", "{\"mode\":\"launcher\"}"));
        assertEquals(KioskExitPin.FORMAT_MESSAGE, KioskExitPin.enterCommandProblem("kiosk.enter", "{\"password\":\"1234\"}"));
        assertEquals("any case and spacing of the type", KioskExitPin.FORMAT_MESSAGE,
                KioskExitPin.enterCommandProblem(" Kiosk.Enter ", "{\"password\":\"letmeout\"}"));
        assertEquals("a number is not a PIN string", KioskExitPin.FORMAT_MESSAGE,
                KioskExitPin.enterCommandProblem("kiosk.enter", "{\"password\":482139}"));
        assertNull("other commands pass", KioskExitPin.enterCommandProblem("device.passcodeReset", "{\"password\":\"1\"}"));
        assertNull("an unreadable payload is the agent's to report", KioskExitPin.enterCommandProblem("kiosk.enter", "not json"));
        assertNull("the configured PIN is filled in by the server",
                KioskExitPin.enterCommandProblem("kiosk.enter", "{\"configuredPin\":true,\"password\":\"1\"}"));
        assertEquals("only true asks for it", KioskExitPin.FORMAT_MESSAGE,
                KioskExitPin.enterCommandProblem("kiosk.enter", "{\"configuredPin\":\"no\",\"password\":\"1\"}"));
    }

    @Test
    public void theConfiguredPinIsPutInAsStoredAndTheRequestKeyRemoved() {
        String sent = "{\"mode\":\"single\",\"configuredPin\":true,\"password\":\"x\"}";
        assertTrue(KioskExitPin.wantsConfiguredPin("kiosk.enter", sent));
        assertFalse(KioskExitPin.wantsConfiguredPin("kiosk.exit", sent));
        assertEquals("{\"mode\":\"single\",\"password\":\"4931\"}", KioskExitPin.withConfiguredPin(sent, "4931"));
        assertEquals("no PIN: none sent, as config.apply does", "{\"mode\":\"single\"}",
                KioskExitPin.withConfiguredPin(sent, null));
    }

    @Test
    public void aChangedPinMustFollowTheRuleEvenFromALegacyOne() {
        assertEquals(KioskExitPin.FORMAT_MESSAGE, KioskExitPin.problem("letmeout2", "letmeout"));
        assertEquals(KioskExitPin.FORMAT_MESSAGE, KioskExitPin.problem("12345", "1234"));
        assertEquals("case and spacing are a change", KioskExitPin.FORMAT_MESSAGE, KioskExitPin.problem("LetMeOut", "letmeout"));
        assertNull(KioskExitPin.problem("482139", "letmeout"));
        assertNull("clearing it is always allowed", KioskExitPin.problem("", "letmeout"));
    }
}
