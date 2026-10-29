/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.util;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UntrustedTextTest {

    @Test
    public void keepsOrdinaryText() {
        assertEquals("3 wrong exit PINs; exit locked for 30 s",
                UntrustedText.oneLine("3 wrong exit PINs; exit locked for 30 s", 200));
    }

    @Test
    public void lineBreaksAndControlCharactersBecomeSingleSpaces() {
        assertEquals("a b", UntrustedText.oneLine("  a\t\tb  ", 200));
        assertEquals("3 wrong exit PINs [31m x",
                UntrustedText.oneLine("3 wrong\r\nexit PINs\u0000\u0007\u001b[31m ‮x", 200));
    }

    @Test
    public void dropsBidiZeroWidthAndSeparatorCharacters() {
        String s = UntrustedText.oneLine("a‮b​c⁦d e﻿", 200);
        assertFalse(s, s.matches("(?s).*[\\p{Cntrl}\\u2028\\u2029\\u202a-\\u202e\\u2066-\\u2069\\u200b\\ufeff].*"));
        assertTrue(s, s.startsWith("a b"));
    }

    @Test
    public void nothingPrintableLeftIsNull() {
        assertNull(UntrustedText.oneLine("\r\n\u0000", 200));
        assertNull(UntrustedText.oneLine(null, 200));
    }

    @Test
    public void isCutToTheLimitWithAnEllipsis() {
        String s = UntrustedText.oneLine(repeat('x', 5000), 200);
        assertEquals(200, s.length());
        assertTrue(s.endsWith("…"));
    }

    @Test
    public void aCutNeverSplitsASurrogatePair() {
        assertEquals(repeat('a', 9) + "…", UntrustedText.oneLine(repeat('a', 9) + "😀", 10));
        assertEquals(repeat('a', 8) + "…", UntrustedText.oneLine(repeat('a', 8) + "😀b", 10));
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }
}
