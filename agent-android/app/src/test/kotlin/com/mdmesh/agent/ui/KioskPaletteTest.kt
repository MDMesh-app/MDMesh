package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launcher's colours stay readable whatever the admin picks. */
class KioskPaletteTest {

    private fun c(hex: String): Int = (0xFF000000 or hex.removePrefix("#").toLong(16)).toInt()

    private val backgrounds = listOf(
        "#FFFFFF", "#000000", "#777777", "#808080", "#0F1115", "#F6F7F9", "#FFD1DC", "#FFF3B0", "#1E88E5",
        "#2E7D32", "#F4B942", "#C62828", "#6A1B9A", "#00838F", "#3A3A3A", "#BDBDBD",
    ).map(::c)
    private val texts = backgrounds + listOf(c("#E7EBF1"), c("#1B2230"), c("#FF00FF"))

    @Test fun `no kiosk colours means v2 dark`() {
        val p = KioskPalette.DEFAULT
        assertEquals(KioskPalette.DEFAULT_BACKGROUND, p.background)
        assertEquals(KioskPalette.DEFAULT_TEXT, p.text)
        assertEquals(p, KioskPalette.of(null, null))
    }

    @Test fun `every admin pair gives readable text, secondary text and buttons`() {
        val failures = buildList {
            for (bg in backgrounds) for (fg in texts) {
                val p = KioskPalette.of(bg, fg)
                val name = "bg %08X fg %08X".format(bg, fg)
                if (Contrast.ratio(p.text, p.background) < Contrast.UI_MIN) add("$name text")
                if (Contrast.ratio(p.secondary, p.background) < Contrast.TEXT_MIN) add("$name secondary")
                if (Contrast.ratio(p.buttonLabel, p.buttonFill) < Contrast.TEXT_MIN) add("$name button label")
                if (Contrast.ratio(p.buttonFill, p.background) < Contrast.UI_MIN) add("$name button edge")
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun `a readable admin text colour is kept`() {
        val white = c("#FFFFFF")
        val brandBlue = c("#1E88E5") // white on it is 3.7:1: between large-text and body-text levels
        assertEquals(white, KioskPalette.of(brandBlue, white).text)
        assertEquals(c("#000000"), KioskPalette.of(white, c("#000000")).text)
    }

    @Test fun `an unreadable admin text colour is replaced`() {
        val yellow = c("#FFF3B0")
        val p = KioskPalette.of(yellow, c("#FFFFFF"))
        assertEquals(KioskPalette.LIGHT_TEXT, p.text)
        val same = KioskPalette.of(c("#2E7D32"), c("#2E7D32"))
        assertTrue(Contrast.ratio(same.text, same.background) >= Contrast.TEXT_MIN)
    }

    @Test fun `the background is made opaque`() {
        val p = KioskPalette.of(0x80FFFFFF.toInt(), 0x00000000)
        assertEquals(c("#FFFFFF"), p.background)
        assertEquals(c("#000000"), p.text)
    }

    @Test fun `secondary text is the text colour at 72 percent when that reads`() {
        val p = KioskPalette.of(c("#000000"), c("#FFFFFF"))
        assertEquals(KioskPalette.blend(c("#FFFFFF"), c("#000000"), 0.72f), p.secondary)
        assertEquals(c("#B8B8B8"), p.secondary)
    }

    @Test fun `ripples are translucent text and background`() {
        val p = KioskPalette.of(c("#000000"), c("#FFFFFF"))
        assertEquals(0x1FFFFFFF, p.ripple)
        assertEquals(0x3D000000, p.buttonRipple)
    }

    @Test fun `the most readable colour always reaches 4_5 to 1`() {
        for (grey in 0..255) {
            val bg = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
            val ratio = Contrast.ratio(KioskPalette.mostReadableOn(bg), bg)
            assertTrue("grey $grey: $ratio", ratio >= Contrast.TEXT_MIN)
        }
    }
}
