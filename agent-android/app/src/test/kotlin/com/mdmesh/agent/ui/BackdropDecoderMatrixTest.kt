package com.mdmesh.agent.ui

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The backdrop decode on every API level the agent supports, normal and low-RAM: every format the console accepts
 * (JPEG, PNG, WebP lossy, lossless and with transparency), EXIF orientation, the size caps, and files that can't be
 * decoded. Fixtures in `src/test/resources/backdrop/` are 96 x 64. Robolectric's native graphics (real decoders)
 * start at API 26; API 24 runs [BackdropDecoderApi24Test] on its stand-in decoder, and on a phone (the checklist).
 * A truncated file isn't here: decoders draw what they got, so the store's SHA-256 check is what stops it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [26, 30, 33, 35])
open class BackdropDecoderMatrixTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val background = 0xFF0F1115.toInt()

    @Before fun clean() {
        app.cacheDir.listFiles()?.forEach { if (it.name.startsWith("fixture-")) it.delete() }
    }

    private fun fixture(name: String): File {
        val bytes = javaClass.getResourceAsStream("/backdrop/$name")!!.use { it.readBytes() }
        return File(app.cacheDir, "fixture-$name").apply { writeBytes(bytes) }
    }

    private fun decode(file: File, screenLong: Int = 48, limits: DecodeLimits = DecodeLimits.NORMAL) =
        BackdropDecoder.decode(file, screenLong, background, limits)

    /** The bounds of a box given in the decoded image's own pixels. */
    private fun box(d: Decoded.Ready, x: Double, y: Double, w: Double, h: Double): ColourBox {
        val iw = d.info.cells.width
        val ih = d.info.cells.height
        val rect = BackdropGeometry.rect(iw, ih, iw.toDouble(), ih.toDouble(), Placement.DEFAULT)
        return d.info.cells.bounds(rect, iw.toDouble(), ih.toDouble(), BackdropCells.Region(x, y, w, h), background)
    }

    private fun ready(name: String, limits: DecodeLimits): Decoded.Ready {
        val got = decode(fixture(name), limits = limits)
        assertTrue("$name decodes (${limits.lowMemory})", got is Decoded.Ready)
        return got as Decoded.Ready
    }

    @Test fun everyFormatDecodesNormalAndLowRam() {
        for (limits in listOf(DecodeLimits.NORMAL, DecodeLimits.LOW_MEMORY)) {
            for (name in listOf("opaque.jpg", "opaque.png", "alpha.png", "lossy.webp", "lossless.webp", "alpha.webp")) {
                val d = ready(name, limits)
                assertEquals("$name: whole, not sampled (small)", 96, d.bitmap.width)
                assertEquals(name, 64, d.bitmap.height)
                assertNotEquals("never a hardware bitmap", "HARDWARE", d.bitmap.config?.name)
                // The cells see the picture: yellow on the left (red high), blue on the right (blue high).
                val left = box(d, 4.0, 40.0, 8.0, 8.0)
                val right = box(d, 84.0, 60.0, 4.0, 2.0)
                assertTrue("$name left ${Integer.toHexString(left.min)}", Color.red(left.min) > 200)
                assertTrue("$name right ${Integer.toHexString(right.max)}", Color.red(right.max) < 120)
                assertEquals("$name cells", 1, d.info.cells.cellSize)
            }
        }
    }

    @Test fun transparencyIsCompositedOverTheKioskColour() {
        for (name in listOf("alpha.png", "alpha.webp")) {
            val d = ready(name, DecodeLimits.NORMAL)
            // The top quarter is fully transparent: the cells there are the kiosk background.
            val top = box(d, 20.0, 2.0, 8.0, 4.0)
            assertEquals(name, ColourBox(background, background), top)
        }
    }

    /** S3: only a low-RAM device draws at 2 bytes a pixel (an opaque picture); everything else at 8 bits a channel. */
    @Test fun onlyLowRamDrawsAt565() {
        for (name in listOf("opaque.jpg", "opaque.png", "lossy.webp")) {
            assertEquals(name, Bitmap.Config.ARGB_8888, ready(name, DecodeLimits.NORMAL).bitmap.config)
            assertEquals(name, Bitmap.Config.RGB_565, ready(name, DecodeLimits.LOW_MEMORY).bitmap.config)
        }
        val alpha = ready("alpha.png", DecodeLimits.LOW_MEMORY)
        assertEquals("transparency kept", Bitmap.Config.ARGB_8888, alpha.bitmap.config)
    }

    /** A flat 64 x 32 picture in [colour], as [format]. */
    private fun flat(colour: Int, format: Bitmap.CompressFormat, name: String): File {
        val src = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(colour)
            setHasAlpha(false)
        }
        return File(app.cacheDir, "fixture-$name").apply { outputStream().use { src.compress(format, 100, it) } }
    }

    /**
     * S3: the 4.5:1 and 3:1 plates are worked out from the cells, with one level of allowance for the GPU. So the
     * cells must hold the colours actually drawn: every drawn pixel lies inside its cell's bounds, normal and
     * low-RAM, for the formats a photo comes in. (Drawing a JPEG at 565 while the cells came from its 8-bit pixels
     * put the drawn colour several levels outside them.)
     */
    @Test fun everyDrawnPixelIsInsideTheCellsTheContrastIsWorkedOutFrom() {
        val odd = Color.rgb(0x13, 0x57, 0x9B) // every channel off the 565 grid
        val files = listOf(
            flat(odd, Bitmap.CompressFormat.JPEG, "odd.jpg"),
            flat(odd, Bitmap.CompressFormat.PNG, "odd.png"),
            fixture("opaque.jpg"),
            fixture("lossy.webp"),
        )
        for (limits in listOf(DecodeLimits.NORMAL, DecodeLimits.LOW_MEMORY)) for (file in files) {
            val d = decode(file, limits = limits) as Decoded.Ready
            assertEquals(1, d.info.cells.cellSize)
            for (y in 0 until d.bitmap.height) for (x in 0 until d.bitmap.width) {
                val b = box(d, x + 0.25, y + 0.25, 0.5, 0.5)
                val px = d.bitmap.getPixel(x, y)
                val where = "${file.name} low=${limits.lowMemory} ($x,$y) ${Integer.toHexString(px)} not in " +
                    "${Integer.toHexString(b.min)}..${Integer.toHexString(b.max)}"
                for (shift in intArrayOf(16, 8, 0)) {
                    val v = (px shr shift) and 0xFF
                    assertTrue(where, v >= ((b.min shr shift) and 0xFF) && v <= ((b.max shr shift) and 0xFF))
                }
            }
        }
    }

    @Test fun exifOrientationIsApplied() {
        // Stored 96 x 64 landscape, shown upright 64 x 96.
        val d = decode(fixture("rotated.jpg")) as Decoded.Ready
        assertEquals(64, d.bitmap.width)
        assertEquals(96, d.bitmap.height)
        // Rotated 90 clockwise: the yellow left half is now the top half.
        val topPixel = d.bitmap.getPixel(32, 10)
        val yellow = Color.red(topPixel) > 200 && Color.blue(topPixel) < 100
        assertTrue("yellow on top: ${Integer.toHexString(topPixel)}", yellow)
    }

    @Test fun anythingUndecodableIsUndecodable() {
        assertEquals(Decoded.Undecodable, decode(fixture("garbage.png")))
        assertEquals(Decoded.Undecodable, decode(File(app.cacheDir, "fixture-missing.png")))
    }

    // --- S2: once the header reads, a failed pixel decode is "not now", never "this image is bad" ---

    private fun decodeWith(pixels: (String, BitmapFactory.Options) -> Bitmap?) =
        BackdropDecoder.decode(fixture("opaque.jpg"), 48, background, DecodeLimits.NORMAL, pixels)

    /** BitmapFactory returns null, not OOM, when it can't allocate: the half-size retry runs, and it decodes. */
    @Test fun aNullFullDecodeIsRetriedAtHalfSize() {
        val samples = mutableListOf<Int>()
        val d = decodeWith { path, o ->
            samples += o.inSampleSize
            if (samples.size == 1) null else BitmapFactory.decodeFile(path, o)
        }
        assertEquals(listOf(1, 2), samples)
        assertTrue("$d", d is Decoded.Ready)
        assertEquals("half size", 48, (d as Decoded.Ready).bitmap.width)
    }

    /** Null, OOM or an error at both sizes: transient (nothing remembered), never Undecodable (Invalid for good). */
    @Test fun aFailedPixelDecodeAfterGoodBoundsIsTransient() {
        val failures = listOf<(String, BitmapFactory.Options) -> Bitmap?>(
            { _, _ -> null },
            { _, _ -> throw OutOfMemoryError("pixels") },
            { _, _ -> throw java.io.IOException("read") },
            { _, _ -> throw IllegalStateException("decoder") },
        )
        for (fail in failures) {
            var calls = 0
            val d = decodeWith { p, o -> calls++; fail(p, o) }
            assertEquals(Decoded.Transient, d)
            assertEquals("both sizes tried", 2, calls)
        }
    }

    @Test fun largeImagesAreCappedAndLowRamHalvedAgain() {
        val big = Bitmap.createBitmap(3000, 2000, Bitmap.Config.RGB_565)
        Canvas(big).drawColor(Color.rgb(200, 30, 30))
        val file = File(app.cacheDir, "fixture-big.jpg")
        file.outputStream().use { big.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        big.recycle()
        // A 4K tablet / TV window.
        val normal = decode(file, screenLong = 3840) as Decoded.Ready
        assertTrue("normal cap: ${normal.bitmap.width}", maxOf(normal.bitmap.width, normal.bitmap.height) <= 2560)
        val low = decode(file, screenLong = 3840, limits = DecodeLimits.LOW_MEMORY) as Decoded.Ready
        assertTrue("low-RAM cap: ${low.bitmap.width}", maxOf(low.bitmap.width, low.bitmap.height) <= 1280)
        assertEquals(Bitmap.Config.RGB_565, low.bitmap.config)
    }

    @Test fun limitsFollowTheDevice() {
        val am = app.getSystemService(ActivityManager::class.java)
        shadowOf(am).setMemoryClass(256)
        assertEquals(DecodeLimits.NORMAL, DecodeLimits.of(app))
        shadowOf(am).setMemoryClass(128)
        assertEquals(DecodeLimits.LOW_MEMORY, DecodeLimits.of(app))
        shadowOf(am).setMemoryClass(512)
        shadowOf(am).setIsLowRamDevice(true)
        assertEquals(DecodeLimits.LOW_MEMORY, DecodeLimits.of(app))
    }
}

/**
 * API 24 (Android 7): Robolectric has no real decoders this far back, so this checks the decode path's own logic
 * there (no newer API in it, sizes, failures); the pixels are checked on 26+ and on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class BackdropDecoderApi24Test {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun fixture(name: String): File {
        val bytes = javaClass.getResourceAsStream("/backdrop/$name")!!.use { it.readBytes() }
        return File(app.cacheDir, "fixture-$name").apply { writeBytes(bytes) }
    }

    @Test fun jpegAndPngDecodeToTheWindowShape() {
        for (limits in listOf(DecodeLimits.NORMAL, DecodeLimits.LOW_MEMORY)) {
            for (name in listOf("opaque.jpg", "opaque.png")) {
                val d = BackdropDecoder.decode(fixture(name), 48, 0xFF0F1115.toInt(), limits)
                assertTrue("$name on API 24: $d", d is Decoded.Ready)
            }
        }
    }

    @Test fun aMissingFileIsUndecodable() {
        // Garbage is checked on 26+: the stand-in decoder here makes a picture of anything.
        val missing = BackdropDecoder.decode(File(app.cacheDir, "none.png"), 48, 0, DecodeLimits.NORMAL)
        assertEquals(Decoded.Undecodable, missing)
    }

    @Test fun limitsFollowTheDevice() {
        val am = app.getSystemService(ActivityManager::class.java)
        shadowOf(am).setIsLowRamDevice(true)
        assertEquals(DecodeLimits.LOW_MEMORY, DecodeLimits.of(app))
    }
}
