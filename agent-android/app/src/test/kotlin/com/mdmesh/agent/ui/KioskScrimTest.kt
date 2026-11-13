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

    private val palettes: List<KioskPalette> by lazy {
        colours.flatMap { bg -> colours.map { fg -> KioskPalette.of(bg, fg) } }
    }
    private val pairs: List<Pair<Int, Int>> by lazy { colours.flatMap { a -> colours.map { b -> a to b } } }

    @Test fun `no controls, no dim, and the dim never passes 30 percent`() {
        assertEquals(0, LegibilityV3.dimStep(KioskPalette.DEFAULT, emptyList()))
        for ((p, ab) in palettes.flatMap { p -> pairs.map { p to it } }) {
            val d = LegibilityV3.dimStep(p, listOf(ColourBox.EMPTY + ab.first + ab.second))
            assertTrue("$p $ab: $d", d in 0..LegibilityV3.DIM_MAX_STEP)
        }
    }

    @Test fun `a plate makes every colour in its box readable, and none is drawn where the dim already is`() {
        val cases = palettes.flatMap { p -> pairs.flatMap { ab -> listOf(0, 8, 15).map { Triple(p, ab, it) } } }
        for ((p, ab, dim) in cases) {
            val box = ColourBox.EMPTY + ab.first + ab.second
            val plate = LegibilityV3.plateStep(p.text, p.background, box, dim)
            // Every corner and channel mix of the box, through dim and plate.
            for (px in corners(box)) {
                val seen = KioskScrim.blend(p.background, KioskScrim.blend(p.background, px, dim), plate)
                assertTrue("$p over $px", Contrast.ratio(p.text, seen) >= Contrast.TEXT_MIN)
            }
        }
        for (p in palettes) {
            val none = LegibilityV3.plateStep(p.text, p.background, ColourBox.EMPTY + p.background, 0)
            assertEquals("the background itself: no plate", 0, none)
        }
    }

    @Test fun `a plate is solid over texture and as light as it can be over a calm patch`() {
        val p = KioskPalette.DEFAULT
        val noise = ColourBox.EMPTY + black + white
        assertEquals("black and white noise", KioskScrim.STEPS, LegibilityV3.plateStep(p.text, p.background, noise, 0))
        val calmLight = ColourBox.EMPTY + 0xFFB0B0B0.toInt() + 0xFFB8B8B8.toInt()
        val step = LegibilityV3.plateStep(p.text, p.background, calmLight, 0)
        assertTrue("translucent over a calm mid-grey: $step", step in 1 until KioskScrim.STEPS)
        val darkTexture = ColourBox.EMPTY + 0xFF000000.toInt() + 0xFF404040.toInt()
        assertEquals("busy but dark enough: none", 0, LegibilityV3.plateStep(p.text, p.background, darkTexture, 0))
    }

    @Test fun `cells follow the spec's size and crop mapping, with a one-cell margin`() {
        val n = 24 * 16
        val white512 = 5 * 24 + 12
        val grid = BackdropCells(24, 16, IntArray(n) { black }, IntArray(n) { if (it == white512) white else black })
        assertEquals(1, grid.cellSize)
        assertEquals(24, grid.cols)
        assertEquals(5, BackdropCells.cellSizeOf(600, 300))
        assertEquals(1, BackdropCells.cellSizeOf(120, 1))
        // A 120 x 80 window on a 24 x 16 image: 5 units per cell. The white cell (12, 5) is in the margin of a box
        // over cell 13 of row 5, and not of one over cell 14.
        val rect = BackdropGeometry.rect(24, 16, 120.0, 80.0, Placement.DEFAULT)
        assertEquals(white, grid.bounds(rect, 120.0, 80.0, BackdropCells.Region(65.0, 25.0, 4.0, 4.0), black).max)
        assertEquals(black, grid.bounds(rect, 120.0, 80.0, BackdropCells.Region(71.0, 25.0, 4.0, 4.0), black).max)
    }

    @Test fun `JPEG orientation is read from the EXIF segment, anything else is upright`() {
        fun res(name: String) = java.io.File(javaClass.getResource("/backdrop/$name")!!.toURI())
        assertEquals(6, JpegOrientation.read(res("rotated.jpg")))
        assertEquals(1, JpegOrientation.read(res("opaque.jpg")))
        assertEquals(1, JpegOrientation.read(res("opaque.png")))
        assertEquals(1, JpegOrientation.read(res("garbage.png")))
        assertEquals(1, JpegOrientation.read(java.io.File("missing.jpg")))
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

    @Test fun `a decode is sampled by powers of two, capped, and sharp enough for the screen`() {
        assertEquals("a phone photo on a phone: half", 2, BackdropDecoder.sampleSize(4000, 3000, 1080, 2560))
        assertEquals("on a 2340 px screen: whole", 1, BackdropDecoder.sampleSize(4000, 3000, 2340, 4096))
        assertEquals("capped at 2560", 2, BackdropDecoder.sampleSize(4000, 3000, 2340, 2560))
        assertEquals("low RAM: 1280", 4, BackdropDecoder.sampleSize(4000, 3000, 2340, 1280))
        assertEquals("a small image: whole", 1, BackdropDecoder.sampleSize(500, 2000, 2340, 2560))
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
