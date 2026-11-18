package com.mdmesh.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRestrictionsTest {
    private val expected = mapOf(
        "serverUrl" to RestrictionValue.Str("https://vhp.example.hr"),
        "kioskMode" to RestrictionValue.Bool(true),
        "pollSeconds" to RestrictionValue.Int32(30),
    )

    @Test
    fun `a read-back matches only the same keys with the same typed values`() {
        val url = "serverUrl" to "https://vhp.example.hr"
        assertTrue(restrictionsMatch(expected, mapOf(url, "kioskMode" to true, "pollSeconds" to 30)))
        assertTrue(restrictionsMatch(emptyMap(), emptyMap()))
        assertFalse("a key missing", restrictionsMatch(expected, mapOf(url, "kioskMode" to true)))
        assertFalse("a key left over", restrictionsMatch(emptyMap(), mapOf("old" to "x")))
        assertFalse("another type", restrictionsMatch(expected, mapOf(url, "kioskMode" to true, "pollSeconds" to "30")))
        assertFalse("another value", restrictionsMatch(expected, mapOf(url, "kioskMode" to false, "pollSeconds" to 30)))
    }

    @Test
    fun `a value never prints itself`() {
        assertFalse(RestrictionValue.Str("device-token-123").toString().contains("device-token-123"))
        assertFalse(expected.toString().contains("vhp.example.hr"))
    }
}
