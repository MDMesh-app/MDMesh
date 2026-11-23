package com.mdmesh.agent.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** On Android 15+ the bar icons sit on the screen's own background: dark icons on light colours, light on dark. */
class SystemBarsTest {

    @Test fun `dark icons on light backgrounds`() {
        assertTrue(SystemBars.isLight(0xFFFFFFFF.toInt()))
        assertTrue(SystemBars.isLight(0xFFF6F7F9.toInt())) // v2 light app
        assertTrue(SystemBars.isLight(0xFFF4B942.toInt())) // amber
    }

    @Test fun `light icons on dark backgrounds`() {
        assertFalse(SystemBars.isLight(0xFF000000.toInt()))
        assertFalse(SystemBars.isLight(0xFF0E1117.toInt())) // kiosk default
        assertFalse(SystemBars.isLight(0xFF1565C0.toInt())) // brand blue
    }
}
