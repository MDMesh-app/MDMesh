package com.mdmesh.policy.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The string rules behind a saved network (no Android types): quoting, key encoding, password fit. */
class WifiKeysTest {

    @Test fun `saved SSIDs lose their quotes, hex SSIDs stay as they are`() {
        assertEquals("Home", WifiKeys.unquote("\"Home\""))
        assertEquals("a \"b\" c", WifiKeys.unquote("\"a \"b\" c\""))
        assertEquals("486f6d65", WifiKeys.unquote("486f6d65"))
        assertEquals("", WifiKeys.unquote(null))
        assertEquals("\"", WifiKeys.unquote("\""))
    }

    @Test fun `a WPA passphrase is quoted, a 64-digit raw key is not`() {
        assertEquals("\"correct horse\"", WifiKeys.pskKey("correct horse"))
        val raw = "0123456789abcdefABCDEF0123456789abcdef0123456789abcdef0123456789"
        assertEquals(64, raw.length)
        assertEquals(raw, WifiKeys.pskKey(raw))
        assertEquals("\"${raw.dropLast(1)}\"", WifiKeys.pskKey(raw.dropLast(1)))
    }

    @Test fun `an ASCII WEP key is quoted, a hex one is not`() {
        assertEquals("\"abcde\"", WifiKeys.wepKey("abcde"))
        assertEquals("0123456789", WifiKeys.wepKey("0123456789"))
        assertEquals("0123456789abcdef0123456789", WifiKeys.wepKey("0123456789abcdef0123456789"))
        assertEquals("\"0123456789abc\"", WifiKeys.wepKey("0123456789abc"))
    }

    @Test fun `security is read from its wire name only`() {
        assertEquals(WifiSecurity.OPEN, WifiSecurity.fromWire("open"))
        assertEquals(WifiSecurity.WEP, WifiSecurity.fromWire("wep"))
        assertEquals(WifiSecurity.PSK, WifiSecurity.fromWire("psk"))
        assertEquals(WifiSecurity.SAE, WifiSecurity.fromWire("sae"))
        assertNull(WifiSecurity.fromWire("PSK"))
        assertNull(WifiSecurity.fromWire("wpa3"))
        assertNull(WifiSecurity.fromWire(null))
    }

    @Test fun `passwords that cannot fit are caught before the platform sees them`() {
        assertTrue(WifiSecurity.OPEN.accepts(""))
        assertFalse(WifiSecurity.PSK.accepts(""))
        assertFalse(WifiSecurity.PSK.accepts("1234567"))
        assertTrue(WifiSecurity.PSK.accepts("12345678"))
        assertTrue(WifiSecurity.PSK.accepts("x".repeat(63)))
        assertFalse(WifiSecurity.PSK.accepts("x".repeat(64)))
        assertTrue(WifiSecurity.PSK.accepts("a".repeat(64)))
        assertFalse(WifiSecurity.WEP.accepts(""))
        assertTrue(WifiSecurity.WEP.accepts("abcde"))
        assertFalse(WifiSecurity.SAE.accepts(""))
        assertTrue(WifiSecurity.SAE.accepts("s"))
    }
}
