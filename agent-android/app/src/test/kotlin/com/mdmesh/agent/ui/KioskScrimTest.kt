package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrim's building blocks beyond the shared vectors ([KioskScrimVectorsTest]), the text plates, the decode
 * geometry and the kiosk colour parser.
 */
class KioskScrimTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val colours = listOf(
        black, white, 0xFF777777.toInt(), 0xFF1E88E5.toInt(), 0xFFF4B942.toInt(), 0xFF2E7D32.toInt(),
        0xFFE53935.toInt(), 0xFF00676B.toInt(),
    )

    @Test fun `blend rounds half up in integers, step 0 is the pixel and step 50 the scrim`() {
        assertEquals(0xFF808080.toInt(), KioskScrim.blend(white, black, 25)) // 127.5 -> 128
        assertEquals(0xFF123456.toInt(), KioskScrim.blend(white, 0xFF123456.toInt(), 0))
        assertEquals(white, KioskScrim.blend(white, 0xFF123456.toInt(), 50))
    }

    @Test fun `the allowance is a count, 2 in 100`() {
        assertEquals(0, KioskScrim.allowed(49))
        assertEquals(1, KioskScrim.allowed(50))
        assertEquals(46, KioskScrim.allowed(2304)) // 48 x 48
    }

    @Test fun `with no samples there is no scrim`() {
        assertEquals(0, KioskScrim.step(KioskPalette.DEFAULT, IntArray(0)))
        assertNull(KioskScrim.covered(white, black, IntArray(0), 0))
    }

    private val palettes: List<KioskPalette> by lazy {
        colours.flatMap { bg -> colours.map { fg -> KioskPalette.of(bg, fg) } }
    }
    private val pairs: List<Pair<Int, Int>> by lazy { colours.flatMap { a -> colours.map { b -> a to b } } }

    @Test fun `every palette over any samples reaches 4_5 at the chosen step, and not one step lower`() {
        for ((p, ab) in palettes.flatMap { p -> pairs.map { p to it } }) {
            val samples = IntArray(50) { if (it % 2 == 0) ab.first else ab.second }
            val step = KioskScrim.step(p, samples)
            fun least(at: Int) =
                listOf(p.text, p.secondary).minOf { KioskScrim.covered(it, p.background, samples, at)!! }
            assertTrue("$p $ab", least(step) >= Contrast.TEXT_MIN)
            assertTrue("$p $ab: the least step", step == 0 || least(step - 1) < Contrast.TEXT_MIN)
        }
    }

    @Test fun `a plate makes every colour in its box readable, and none is drawn where the scrim already is`() {
        val cases = palettes.flatMap { p -> pairs.flatMap { ab -> listOf(0, 20, 40).map { Triple(p, ab, it) } } }
        for ((p, ab, scrim) in cases) {
            val box = ColourBox.EMPTY + ab.first + ab.second
            val plate = KioskScrim.plateStep(p.text, p.background, box, scrim)
            // Every corner and channel mix of the box, through scrim and plate.
            for (px in corners(box)) {
                val seen = KioskScrim.blend(p.background, KioskScrim.blend(p.background, px, scrim), plate)
                assertTrue("$p over $px", Contrast.ratio(p.text, seen) >= Contrast.TEXT_MIN)
            }
        }
        for (p in palettes) {
            val none = KioskScrim.plateStep(p.text, p.background, ColourBox.EMPTY + p.background, 0)
            assertEquals("the background itself: no plate", 0, none)
        }
    }

    private fun corners(box: ColourBox): List<Int> {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        val out = mutableListOf<Int>()
        val rs = listOf(ch(box.min, 16), ch(box.max, 16))
        val gs = listOf(ch(box.min, 8), ch(box.max, 8))
        val bs = listOf(ch(box.min, 0), ch(box.max, 0))
        for (r in rs) for (g in gs) for (b in bs) out += 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        return out
    }

    @Test fun `over composites a transparent pixel onto the background`() {
        assertEquals(white, KioskScrim.over(0x00000000, white))
        assertEquals(0xFF808080.toInt(), KioskScrim.over(0x80FFFFFF.toInt(), black))
        assertEquals(0xFF123456.toInt(), KioskScrim.over(0xFF123456.toInt(), black))
    }

    @Test fun `a decode is cropped to the window's shape and capped`() {
        assertEquals(1080 to 2340, BackdropDecoder.outputSize(4000, 6000, 1080, 2340, 2560))
        assertEquals(1182 to 2560, BackdropDecoder.outputSize(4000, 9000, 1600, 3464, 2560))
        assertEquals(1280 to 800, BackdropDecoder.outputSize(6000, 4000, 2560, 1600, 1280))
        // Never upscaled in memory: a small image keeps its own pixels.
        assertEquals(500 to 1083, BackdropDecoder.outputSize(500, 2000, 1080, 2340, 2560))
        assertEquals(1, BackdropDecoder.coverSample(1080, 2340, 1080, 2340))
        assertEquals(4, BackdropDecoder.coverSample(8000, 8000, 1080, 1920))
        assertEquals(48 to 22, BackdropDecoder.sampleGrid(2340, 1080))
        assertEquals(22 to 48, BackdropDecoder.sampleGrid(1080, 2340))
    }

    @Test fun `EXIF orientations map to rotations`() {
        assertEquals(BackdropDecoder.Orientation.ROTATE_90, BackdropDecoder.Orientation.of(6))
        assertEquals(BackdropDecoder.Orientation.ROTATE_270, BackdropDecoder.Orientation.of(8))
        assertEquals(BackdropDecoder.Orientation.ROTATE_180, BackdropDecoder.Orientation.of(3))
        assertEquals(BackdropDecoder.Orientation.NORMAL, BackdropDecoder.Orientation.of(0))
    }

    @Test fun `kiosk colours parse only as both the console and Android read them`() {
        assertEquals(white, KioskPalette.parse("#FFFFFF"))
        assertEquals("alpha dropped", white, KioskPalette.parse("#80FFFFFF"))
        assertEquals(0xFF00676B.toInt(), KioskPalette.parse("#00676b"))
        assertEquals("Android's names, any case", 0xFF808000.toInt(), KioskPalette.parse("OLIVE"))
        for (bad in listOf("#FFF", "FFFFFF", " #FFFFFF", "#+FFFFF", "#GGGGGG", "", "#", "transparent", null)) {
            assertNull("$bad", KioskPalette.parse(bad))
        }
    }
}
