package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Long IDs and URLs wrap between their parts at large font sizes; what is copied or read stays the value. */
class BreakHintsTest {

    private val zwsp = '​'

    @Test fun `a break opportunity after each separator`() {
        assertEquals("https:$zwsp/$zwsp/${zwsp}mdm.${zwsp}example.${zwsp}com", breakHints("https://mdm.example.com"))
        assertEquals("dev_${zwsp}7f3a9c21e4b84d0e", breakHints("dev_7f3a9c21e4b84d0e"))
    }

    @Test fun `removing the hints gives the value back`() {
        val value = "https://mdm.example.com/x_y-z"
        assertEquals(value, breakHints(value).replace(zwsp.toString(), ""))
    }
}
