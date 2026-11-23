package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ContrastTest {

    @Test fun `black on white is 21 to 1 and order does not matter`() {
        assertEquals(21.0, Contrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.001)
        assertEquals(21.0, Contrast.ratio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.001)
    }

    @Test fun `a colour against itself is 1 to 1`() {
        assertEquals(1.0, Contrast.ratio(0xFF586073.toInt(), 0xFF586073.toInt()), 0.0001)
    }

    @Test fun `matches published WCAG values`() {
        // #767676 is the classic lightest grey that passes 4.5:1 on white (4.54:1).
        assertEquals(4.54, Contrast.ratio(0xFF767676.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        // The old status-screen label colour on its background: the survey's 3.25:1 failure.
        assertEquals(3.25, Contrast.ratio(0xFF5C6675.toInt(), 0xFF0E1117.toInt()), 0.01)
    }

    @Test fun `alpha is ignored`() {
        assertEquals(
            Contrast.ratio(0xFF15181D.toInt(), 0xFFFFFFFF.toInt()),
            Contrast.ratio(0x0015181D, 0x80FFFFFF.toInt()),
            0.0001,
        )
    }

    /** PR #192 N1: the looked-up transfer is the WCAG formula itself, to the last bit, on every channel level. */
    @Test fun `luminance is exactly the WCAG formula on every level`() {
        fun linear(level: Int): Double {
            val c = level / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        for (r in 0..255 step 3) for (g in 0..255 step 5) for (b in 0..255 step 7) {
            val expected = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
            assertEquals(expected, Contrast.luminance((0xFF shl 24) or (r shl 16) or (g shl 8) or b), 0.0)
        }
        for (v in 0..255) {
            assertEquals(0.2126 * linear(v), Contrast.luminance((0xFF shl 24) or (v shl 16)), 0.0)
            assertEquals(0.7152 * linear(v), Contrast.luminance((0xFF shl 24) or (v shl 8)), 0.0)
            assertEquals(0.0722 * linear(v), Contrast.luminance((0xFF shl 24) or v), 0.0)
        }
    }
}
