package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The adaptive scrim keeps the launcher's text at 4.5:1 over any image, and no darker than needed. */
class KioskScrimTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test fun `an image like the background needs no scrim`() {
        val p = KioskPalette.DEFAULT
        assertEquals(0f, KioskScrim.alpha(p, BackdropStats(p.background, p.background, p.background)), 0f)
    }

    @Test fun `a busy black and white image gets the least scrim that reads`() {
        for (p in listOf(KioskPalette.DEFAULT, KioskPalette.of(white, 0xFF1B2230.toInt()))) {
            val stats = BackdropStats(black, white, white)
            val a = KioskScrim.alpha(p, stats)
            assertTrue("some scrim", a > 0f)
            assertTrue("readable at $a", KioskScrim.readable(p, stats, a))
            assertFalse("not at less", KioskScrim.readable(p, stats, a - 0.02f))
        }
    }

    @Test fun `every palette over any extremes reads at the chosen alpha`() {
        val colours = listOf(
            black, white, 0xFF777777.toInt(), 0xFF1E88E5.toInt(), 0xFFF4B942.toInt(), 0xFF2E7D32.toInt(),
        )
        val cases = colours.flatMap { bg -> colours.map { fg -> KioskPalette.of(bg, fg) } }
            .flatMap { p ->
                colours.flatMap { dark -> colours.map { light -> p to BackdropStats(dark, light, light) } }
            }
        for ((p, stats) in cases) {
            val a = KioskScrim.alpha(p, stats)
            assertTrue("$p over $stats at $a", KioskScrim.readable(p, stats, a))
        }
    }

    @Test fun `stats take the 2nd and 98th luminance percentiles and the top rows`() {
        val pixels = IntArray(100) { i -> 0xFF000000.toInt() or (i * 0x020202) }
        val stats = KioskScrim.stats(pixels, width = 10, topRows = 1)
        assertEquals(0xFF000000.toInt() or (1 * 0x020202), stats.darkest)
        assertEquals(0xFF000000.toInt() or (97 * 0x020202), stats.lightest)
        assertEquals(0xFF000000.toInt() or (5 * 0x020202), stats.top)
    }

    @Test fun `a decode covers the window with the largest power-of-two subsample`() {
        assertEquals(1, KioskBackdrop.coverSample(1080, 2340, 1080, 2340))
        assertEquals(2, KioskBackdrop.coverSample(4096, 4096, 1080, 2340 / 2))
        assertEquals(4, KioskBackdrop.coverSample(8000, 8000, 1080, 1920))
        assertEquals(1, KioskBackdrop.coverSample(500, 500, 1080, 2340))
    }

    @Test fun `EXIF orientations map to rotations`() {
        assertEquals(KioskBackdrop.Orientation.ROTATE_90, KioskBackdrop.Orientation.of(6))
        assertEquals(KioskBackdrop.Orientation.ROTATE_270, KioskBackdrop.Orientation.of(8))
        assertEquals(KioskBackdrop.Orientation.ROTATE_180, KioskBackdrop.Orientation.of(3))
        assertEquals(KioskBackdrop.Orientation.NORMAL, KioskBackdrop.Orientation.of(0))
        assertEquals(90, KioskBackdrop.Orientation.of(6).degrees)
    }
}
