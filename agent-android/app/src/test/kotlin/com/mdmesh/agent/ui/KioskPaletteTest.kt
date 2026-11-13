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
                if (Contrast.ratio(p.text, p.background) < Contrast.TEXT_MIN) add("$name text")
                if (Contrast.ratio(p.secondary, p.background) < Contrast.TEXT_MIN) add("$name secondary")
                if (Contrast.ratio(p.buttonLabel, p.buttonFill) < Contrast.TEXT_MIN) add("$name button label")
                if (Contrast.ratio(p.buttonFill, p.background) < Contrast.UI_MIN) add("$name button edge")
                if (Contrast.ratio(p.focus, p.background) < Contrast.UI_MIN) add("$name focus ring")
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun `a readable admin text colour is kept`() {
        val white = c("#FFFFFF")
        assertEquals(white, KioskPalette.of(c("#1565C0"), white).text) // 5.75:1
        assertEquals(c("#000000"), KioskPalette.of(white, c("#000000")).text)
    }

    @Test fun `admin text under 4_5 to 1 is replaced, as all text on the launcher is body size`() {
        val white = c("#FFFFFF")
        // 3.7:1 and 4.2:1: readable as large text only, and the launcher has no large text.
        for (bg in listOf(c("#1E88E5"), c("#E53935"))) {
            val p = KioskPalette.of(bg, white)
            assertEquals(KioskPalette.mostReadableOn(bg), p.text)
            assertEquals("one colour for all the launcher's text", p.text, p.buttonFill)
        }
    }

    /** Every background and text colour across the colour wheel: no text under 4.5:1, no ring under 3:1. */
    @Test fun `a sweep of admin colours never gives text under 4_5 to 1`() {
        val colours = buildList {
            for (grey in 0..255 step 5) add(rgb(grey, grey, grey))
            for (h in 0 until 360 step 15) {
                for (sat in listOf(0.35f, 0.7f, 1f)) {
                    for (v in listOf(0.3f, 0.55f, 0.8f, 1f)) add(hsv(h.toFloat(), sat, v))
                }
            }
        }
        var worstText = Double.MAX_VALUE
        var worstRing = Double.MAX_VALUE
        for (bg in colours) for (fg in colours) {
            val p = KioskPalette.of(bg, fg)
            val bgTo = { c: Int -> Contrast.ratio(c, p.background) }
            worstText = minOf(worstText, bgTo(p.text), bgTo(p.secondary), Contrast.ratio(p.buttonLabel, p.buttonFill))
            worstRing = minOf(worstRing, bgTo(p.focus), bgTo(p.buttonFill))
        }
        assertTrue("worst text $worstText", worstText >= Contrast.TEXT_MIN)
        assertTrue("worst ring $worstRing", worstRing >= Contrast.UI_MIN)
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = Math.round((f + m) * 255)
        return rgb(ch(r), ch(g), ch(b))
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

    @Test fun `secondary text is the text colour at 80 percent when that reads`() {
        val p = KioskPalette.of(c("#000000"), c("#FFFFFF"))
        assertEquals(KioskPalette.blend(c("#FFFFFF"), c("#000000"), 0.8f), p.secondary)
        assertEquals(c("#CCCCCC"), p.secondary)
    }

    @Test fun `the focus ring is the text colour`() {
        val p = KioskPalette.of(c("#E53935"), c("#FFFFFF"))
        assertEquals(p.buttonFill, p.focus)
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
