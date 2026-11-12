package com.mdmesh.agent.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import android.graphics.Matrix
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * How large a backdrop may be decoded on this device. The window's size, capped: 2560 px on the long edge, 1280 px
 * on a low-RAM device or one whose app heap class is 128 MB or less (Android Go, old 32-bit phones), where a
 * picture without transparency also takes 2 bytes a pixel (RGB_565) instead of 4.
 */
data class DecodeLimits(val maxEdge: Int, val lowMemory: Boolean) {
    companion object {
        const val MAX_EDGE = 2560
        const val LOW_MEMORY_EDGE = 1280
        private const val LOW_MEMORY_CLASS_MB = 128

        val NORMAL = DecodeLimits(MAX_EDGE, lowMemory = false)
        val LOW_MEMORY = DecodeLimits(LOW_MEMORY_EDGE, lowMemory = true)

        fun of(context: Context): DecodeLimits {
            val am = context.getSystemService(ActivityManager::class.java) ?: return LOW_MEMORY
            return if (am.isLowRamDevice || am.memoryClass <= LOW_MEMORY_CLASS_MB) LOW_MEMORY else NORMAL
        }
    }
}

/** What the launcher keeps of a decoded backdrop besides the bitmap: its [cells], composited over [background]. */
class BackdropInfo(
    /** The kiosk background the image was composited over. */
    val background: Int,
    val cells: BackdropCells,
    /** No pixel has any transparency: the cells are the same over any background colour. */
    val opaque: Boolean,
) {
    /** The cells hold for a kiosk in [background]: its own, or any for an opaque image (most photos). */
    fun validFor(background: Int): Boolean = opaque || background == this.background
}

/**
 * The image as square cells, each with the per-channel minimum and maximum of its pixels (composited over the
 * background), exactly as `docs/kiosk-background-scrim.md` (The image as cells) defines them: `cellSize = max(1,
 * ceil(max(width, height) / 120))`. [bounds] answers "which colours can be under this box" without the pixels.
 */
class BackdropCells(val width: Int, val height: Int, private val mins: IntArray, private val maxs: IntArray) {
    val cellSize: Int = cellSizeOf(width, height)
    val cols: Int = ceilDiv(width, cellSize)
    val rows: Int = ceilDiv(height, cellSize)

    init {
        require(mins.size == cols * rows && maxs.size == cols * rows) { "cells: $cols x $rows" }
    }

    /** A box in a window: left, top, width, height, in the window's unit. */
    data class Region(val x: Double, val y: Double, val w: Double, val h: Double)

    /**
     * The bounds of [r] in a [windowW] x [windowH] window where the image lies at [rect]: the cells the box touches
     * (clipped to the image) plus one cell around them, and [background] when the box, clipped to the window, reaches
     * the letterbox. A box over neither is [background] alone.
     */
    fun bounds(rect: ImageRect, windowW: Double, windowH: Double, r: Region, background: Int): ColourBox {
        val x0 = maxOf(0.0, (r.x - rect.x) / rect.scale)
        val x1 = minOf(width.toDouble(), (r.x + r.w - rect.x) / rect.scale)
        val y0 = maxOf(0.0, (r.y - rect.y) / rect.scale)
        val y1 = minOf(height.toDouble(), (r.y + r.h - rect.y) / rect.scale)
        var box = ColourBox.EMPTY
        var any = false
        if (x1 > x0 && y1 > y0) {
            any = true
            val i0 = maxOf(0, floor(x0 / cellSize).toInt() - 1)
            val i1 = minOf(cols - 1, ceil(x1 / cellSize).toInt())
            val j0 = maxOf(0, floor(y0 / cellSize).toInt() - 1)
            val j1 = minOf(rows - 1, ceil(y1 / cellSize).toInt())
            for (j in j0..j1) for (i in i0..i1) box += ColourBox(mins[j * cols + i], maxs[j * cols + i])
        }
        if (reachesLetterbox(rect, windowW, windowH, r) || !any) box += background
        return box
    }

    /** [r], clipped to the window, reaches outside the image's rectangle. */
    private fun reachesLetterbox(rect: ImageRect, windowW: Double, windowH: Double, r: Region): Boolean {
        val l = maxOf(0.0, r.x)
        val t = maxOf(0.0, r.y)
        val rr = minOf(windowW, r.x + r.w)
        val b = minOf(windowH, r.y + r.h)
        return rr > l && b > t && (l < rect.x || t < rect.y || rr > rect.x + rect.w || b > rect.y + rect.h)
    }

    companion object {
        const val CELLS_ON_LONG_EDGE = 120

        fun cellSizeOf(width: Int, height: Int): Int = maxOf(1, ceilDiv(maxOf(width, height), CELLS_ON_LONG_EDGE))

        private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

        /**
         * From a [width] x [height] image whose rows [row] fills in, already composited over the background (one
         * row at a time: no copy of the whole image in memory).
         */
        fun of(width: Int, height: Int, row: (y: Int, into: IntArray) -> Unit): BackdropCells {
            val cell = cellSizeOf(width, height)
            val cols = ceilDiv(width, cell)
            val rows = ceilDiv(height, cell)
            val lo = Array(CHANNELS) { IntArray(cols * rows) { BYTE } }
            val hi = Array(CHANNELS) { IntArray(cols * rows) }
            val line = IntArray(width)
            for (y in 0 until height) {
                row(y, line)
                val base = (y / cell) * cols
                for (x in 0 until width) widen(lo, hi, base + x / cell, line[x])
            }
            fun pack(a: Array<IntArray>, i: Int) =
                OPAQUE or (a[0][i] shl SHIFTS[0]) or (a[1][i] shl SHIFTS[1]) or (a[2][i] shl SHIFTS[2])
            val n = cols * rows
            return BackdropCells(width, height, IntArray(n) { pack(lo, it) }, IntArray(n) { pack(hi, it) })
        }

        /** Cell [i]'s per-channel range grown to hold [px]. */
        private fun widen(lo: Array<IntArray>, hi: Array<IntArray>, i: Int, px: Int) {
            for (ch in 0 until CHANNELS) {
                val v = (px shr SHIFTS[ch]) and BYTE
                if (v < lo[ch][i]) lo[ch][i] = v
                if (v > hi[ch][i]) hi[ch][i] = v
            }
        }

        private const val CHANNELS = 3
        private val SHIFTS = intArrayOf(16, 8, 0)
        private const val BYTE = 0xFF
        private const val OPAQUE = 0xFF000000.toInt()
    }
}

/** A decode's outcome. */
sealed interface Decoded {
    class Ready(val bitmap: Bitmap, val info: BackdropInfo) : Decoded

    /**
     * Not an image this device can read: its header (format and size) doesn't decode, or the file is gone. The
     * store decides which: a file that still matches its hash is a bad image for good.
     */
    data object Undecodable : Decoded

    /**
     * An image whose header reads, but whose pixels couldn't be decoded now, even at half the size: memory short
     * (BitmapFactory returns null as often as it throws), a read error, a decoder failure. Nothing is remembered: show
     * nothing new, keep what is shown, and try again on the next want.
     */
    data object Transient : Decoded
}

/**
 * Decodes a cached backdrop with BitmapFactory only (API 24 and up, no ImageDecoder paths): JPEG, PNG and WebP
 * (lossy, lossless, with transparency). Never a HARDWARE bitmap (it is drawn into software canvases and read back
 * for the cells), never larger than [DecodeLimits] allow, upright by a JPEG's EXIF orientation. The whole image, at a
 * power-of-two sample: fill or fit, the focal point and the window's shape (a rotation) are placed when drawn
 * ([BackdropGeometry]), from the same bitmap.
 *
 * The legibility cells are always worked out from the very pixels that are drawn: 8 bits a channel, or on a low-RAM
 * device the 2-byte (RGB_565) copy it draws, read back. So the one level of allowance the contrast rule keeps for
 * the GPU is never spent on a format change.
 */
object BackdropDecoder {

    /**
     * Decodes [file] whole, for a screen whose long edge is [screenLong] px, over [background]. Off the main thread.
     * The window's shape doesn't matter: fill or fit, focal point and rotation are drawn from the same bitmap.
     * [pixels] is BitmapFactory's full decode, a seam for tests (the header is always read by BitmapFactory).
     */
    fun decode(
        file: File,
        screenLong: Int,
        background: Int,
        limits: DecodeLimits,
        pixels: (String, BitmapFactory.Options) -> Bitmap? = BitmapFactory::decodeFile,
    ): Decoded {
        val request = Request(file, maxOf(1, screenLong), background, limits, pixels)
        // Only a header that doesn't read is the image's fault; everything after it is "not now".
        val bounds = boundsOf(file) ?: return Decoded.Undecodable
        for (extraSample in EXTRA_SAMPLES) {
            val decoded = try {
                decodeSized(request, bounds, extraSample)
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            }
            if (decoded != null) return decoded
        }
        return Decoded.Transient
    }

    /** Full size, then half: a null or a failure at full size may be only the memory it needed. */
    private val EXTRA_SAMPLES = intArrayOf(1, 2)

    private class Request(
        val file: File,
        val screenLong: Int,
        val background: Int,
        val limits: DecodeLimits,
        val pixels: (String, BitmapFactory.Options) -> Bitmap?,
    )

    /** The header (format and size), or null when [file] is gone or isn't an image BitmapFactory reads. */
    private fun boundsOf(file: File): BitmapFactory.Options? = runCatching {
        if (!file.isFile) return@runCatching null // gone (the cache was cleared): the store fetches it again
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        bounds.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }.getOrNull()

    private fun decodeSized(r: Request, bounds: BitmapFactory.Options, extraSample: Int): Decoded.Ready? {
        val orientation = exifOrientation(r.file, bounds.outMimeType)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, r.screenLong, r.limits.maxEdge) * extraSample
            // 8 bits a channel, the sRGB values the console's rule reads.
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
            // An embedded profile (Display P3 included) converted to sRGB; Android 7 ignores profiles, the same as
            // reading the pixels as sRGB.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        }
        val sampled = r.pixels(r.file.path, options) ?: return null
        val full = orientation.apply(sampled)
        // A low-RAM device draws a picture without transparency at 2 bytes a pixel (already capped at 1280 px), and
        // its cells come from that copy: the colours it draws. Elsewhere the 8-bit bitmap is drawn as decoded.
        val drawn = if (r.limits.lowMemory && !full.hasAlpha()) {
            full.copy(Bitmap.Config.RGB_565, false)?.also { full.recycle() } ?: full
        } else {
            full
        }
        return Decoded.Ready(drawn, infoOf(drawn, r.background))
    }

    /**
     * The power-of-two subsample for an [iw] x [ih] image: at least enough that its long edge is at most [maxEdge],
     * and more while its short edge would still be at least the screen's long edge [screenLong] (enough to fill the
     * screen sharp in either orientation).
     */
    fun sampleSize(iw: Int, ih: Int, screenLong: Int, maxEdge: Int): Int {
        var sample = 1
        while (maxOf(iw, ih) / sample > maxEdge) sample *= 2
        while (minOf(iw, ih) / (sample * 2) >= screenLong) sample *= 2
        return sample
    }

    private fun infoOf(bitmap: Bitmap, background: Int): BackdropInfo {
        val w = bitmap.width
        var opaque = true
        val cells = BackdropCells.of(w, bitmap.height) { y, into ->
            bitmap.getPixels(into, 0, w, 0, y, w, 1)
            for (i in into.indices) {
                if (into[i] ushr ALPHA_SHIFT != OPAQUE_ALPHA) opaque = false
                into[i] = KioskScrim.over(into[i], background)
            }
        }
        return BackdropInfo(background, cells, opaque)
    }

    private const val ALPHA_SHIFT = 24
    private const val OPAQUE_ALPHA = 0xFF

    /** Rotation (and mirroring) from a JPEG's EXIF orientation ([JpegOrientation]); none for other formats. */
    private fun exifOrientation(file: File, mime: String?): Orientation =
        if (mime == "image/jpeg") Orientation.of(JpegOrientation.read(file)) else Orientation.NORMAL


    /** An EXIF orientation as a rotation plus an optional mirror. */
    enum class Orientation(val degrees: Int, private val mirror: Boolean) {
        NORMAL(NONE, false),
        MIRROR(NONE, true),
        ROTATE_180(HALF, false),
        FLIP(HALF, true),
        TRANSPOSE(THREE_QUARTERS, true), // mirrored first, then turned: the main diagonal
        ROTATE_90(QUARTER, false),
        TRANSVERSE(QUARTER, true),
        ROTATE_270(THREE_QUARTERS, false),
        ;

        fun apply(bitmap: Bitmap): Bitmap {
            if (this == NORMAL) return bitmap
            val m = Matrix()
            if (mirror) m.postScale(-1f, 1f)
            m.postRotate(degrees.toFloat())
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (rotated !== bitmap) bitmap.recycle()
            return rotated
        }

        companion object {
            /** From the EXIF orientation tag (1..8, in that order; anything else is [NORMAL]). */
            fun of(exif: Int): Orientation = entries.getOrNull(exif - 1) ?: NORMAL
        }
    }
}

private const val NONE = 0
private const val QUARTER = 90
private const val HALF = 180
private const val THREE_QUARTERS = 270
