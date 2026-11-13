package com.mdmesh.agent.ui

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The launcher's own table of colour names reads them as Android's Color.parseColor does, old and new. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class KioskColourNamesTest {
    @Test fun namesMatchColorParseColor() {
        val names = listOf(
            "black", "darkgray", "gray", "lightgray", "white", "red", "green", "blue", "yellow", "cyan", "magenta",
            "aqua", "fuchsia", "darkgrey", "grey", "lightgrey", "lime", "maroon", "navy", "olive", "purple", "silver",
            "teal",
        )
        for (name in names) assertEquals(name, Color.parseColor(name), KioskPalette.parse(name))
        for (hex in listOf("#123456", "#80ABCDEF")) {
            assertEquals(hex, Color.parseColor(hex) or 0xFF000000.toInt(), KioskPalette.parse(hex))
        }
    }
}
