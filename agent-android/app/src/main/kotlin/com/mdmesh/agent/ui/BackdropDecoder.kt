package com.mdmesh.agent.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import java.io.File
import kotlin.math.roundToInt

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

/**
 * What the launcher keeps of a decoded backdrop besides the bitmap: the scrim's samples, the colour under the status
 * bar, and a coarse map of colour ranges for the text plates. All of it composited over [background].
 */
class BackdropInfo(
    /** The kiosk background the image was composited over; a different one needs a new decode. */
    val background: Int,
    /** The scrim's samples ([KioskScrim]): 48 along the long edge, row-major. */
    val samples: IntArray,
    /** A typical colour (median luminance) of the top sample row, under the status bar. */
    val top: Int,
    val detail: BackdropDetail,
    /** The decoded bitmap's size: drawn centre-cropped onto the launcher, which may be a little off its shape. */
    val width: Int,
    val height: Int,
) {
    /** The colours under [rect] of a [viewW] x [viewH] launcher root that draws the bitmap centre-cropped. */
    fun box(viewW: Int, viewH: Int, rect: RectF): ColourBox {
        val scale = maxOf(viewW / width.toFloat(), viewH / height.toFloat())
        val dx = (viewW - width * scale) / 2f
        val dy = (viewH - height * scale) / 2f
        val w = width * scale
        val h = height * scale
        return detail.box((rect.left - dx) / w, (rect.top - dy) / h, (rect.right - dx) / w, (rect.bottom - dy) / h)
    }
}

/**
 * Per cell of a grid over the window: the range of every pixel's colour ([ColourBox]). [box] answers "which colours
 * can be under this rectangle" without keeping or reading the pixels again.
 */
class BackdropDetail(
    private val cols: Int,
    private val rows: Int,
    private val mins: IntArray,
    private val maxs: IntArray,
) {

    /**
     * The colours under the part of the window from [left], [top] to [right], [bottom] (fractions 0..1 of its width
     * and height), plus one cell around it: drawing scales the bitmap with filtering, which mixes neighbours in.
     */
    fun box(left: Float, top: Float, right: Float, bottom: Float): ColourBox {
        val c0 = (left * cols).toInt().minus(1).coerceIn(0, cols - 1)
        val c1 = (right * cols).toInt().plus(1).coerceIn(0, cols - 1)
        val r0 = (top * rows).toInt().minus(1).coerceIn(0, rows - 1)
        val r1 = (bottom * rows).toInt().plus(1).coerceIn(0, rows - 1)
        var box = ColourBox.EMPTY
        for (r in r0..r1) for (c in c0..c1) box += ColourBox(mins[r * cols + c], maxs[r * cols + c])
        return box
    }

    companion object {
        /** Cells along the long edge: about 8dp each on a phone. */
        const val CELLS = 120

        /**
         * From a [width] x [height] image whose opaque, composited rows [row] fills in (one row at a time: no copy
         * of the whole image in memory).
         */
        fun of(width: Int, height: Int, row: (y: Int, into: IntArray) -> Unit): BackdropDetail {
            val cell = maxOf(1, maxOf(width, height) / CELLS)
            val cols = (width + cell - 1) / cell
            val rows = (height + cell - 1) / cell
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
            return BackdropDetail(cols, rows, IntArray(n) { pack(lo, it) }, IntArray(n) { pack(hi, it) })
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

    /** The file isn't an image this device can read (or is gone, or cut short): the store decides which. */
    data object Undecodable : Decoded

    /** Not enough memory, even at half the size: show nothing new, keep what is shown. */
    data object OutOfMemory : Decoded
}

/**
 * Decodes a cached backdrop with BitmapFactory only (API 24 and up, no ImageDecoder paths): JPEG, PNG and WebP
 * (lossy, lossless, with transparency). Never a HARDWARE bitmap (it is drawn into software canvases and read back
 * for the samples), never larger than [DecodeLimits] allow, upright by its EXIF orientation, cropped to exactly the
 * part of the image the window shows (centre crop), so a rotation decodes again for the new shape.
 */
object BackdropDecoder {

    /** Decodes [file] for a [width] x [height] window over [background]. Off the main thread. */
    fun decode(file: File, width: Int, height: Int, background: Int, limits: DecodeLimits): Decoded {
        val request = Request(file, maxOf(1, width), maxOf(1, height), background, limits)
        return try {
            decodeOrNull(request, extraSample = 1) ?: Decoded.Undecodable
        } catch (_: OutOfMemoryError) {
            try {
                decodeOrNull(request, extraSample = 2) ?: Decoded.Undecodable
            } catch (_: OutOfMemoryError) {
                Decoded.OutOfMemory
            }
        } catch (_: Exception) {
            Decoded.Undecodable
        }
    }

    private class Request(
        val file: File,
        val width: Int,
        val height: Int,
        val background: Int,
        val limits: DecodeLimits,
    )

    private fun decodeOrNull(r: Request, extraSample: Int): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(r.file.path, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) decodeSized(r, bounds, extraSample) else null
    }

    private fun decodeSized(r: Request, bounds: BitmapFactory.Options, extraSample: Int): Decoded? {
        val file = r.file
        val width = r.width
        val height = r.height
        val limits = r.limits
        val orientation = exifOrientation(file, bounds.outMimeType)
        val sideways = orientation.degrees % HALF_TURN != 0
        val (iw, ih) = if (sideways) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        val (outW, outH) = outputSize(iw, ih, width, height, limits.maxEdge)
        val (cropW, cropH) = cropSize(iw, ih, width, height)
        val sample = coverSample(cropW, cropH, outW, outH) * extraSample
        val opaqueFormat = bounds.outMimeType == "image/jpeg"
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (opaqueFormat) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val sampled = BitmapFactory.decodeFile(file.path, options) ?: return null
        val upright = orientation.apply(sampled)
        val cropped = cropTo(upright, width, height, outW / extraSample, outH / extraSample)
        val bitmap = if (limits.lowMemory && cropped.config != Bitmap.Config.RGB_565 && !cropped.hasAlpha()) {
            cropped.copy(Bitmap.Config.RGB_565, false)?.also { cropped.recycle() } ?: cropped
        } else {
            cropped
        }
        return Decoded.Ready(bitmap, infoOf(bitmap, r.background))
    }

    /** The window's size, scaled down so the long edge is at most [maxEdge]. */
    fun outputSize(iw: Int, ih: Int, w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val (cw, ch) = cropSize(iw, ih, w, h)
        // Never larger than the image's own pixels (no upscaling in memory), nor than the cap.
        val f = minOf(1f, maxEdge / maxOf(w, h).toFloat(), cw / w.toFloat(), ch / h.toFloat())
        return maxOf(1, (w * f).roundToInt()) to maxOf(1, (h * f).roundToInt())
    }

    /** The part of an [iw] x [ih] image a [w] x [h] window shows with CENTER_CROP, in image pixels. */
    fun cropSize(iw: Int, ih: Int, w: Int, h: Int): Pair<Int, Int> {
        val scale = maxOf(w / iw.toFloat(), h / ih.toFloat())
        val cw = minOf(iw, (w / scale).roundToInt()).coerceAtLeast(1)
        val ch = minOf(ih, (h / scale).roundToInt()).coerceAtLeast(1)
        return cw to ch
    }

    /** The largest power-of-two subsample of an [iw] x [ih] area that still covers [w] x [h]. */
    fun coverSample(iw: Int, ih: Int, w: Int, h: Int): Int {
        var sample = 1
        while (iw / (sample * 2) >= w && ih / (sample * 2) >= h) sample *= 2
        return sample
    }

    /** The centre of [src] with the window's aspect, scaled to [outW] x [outH]; [src] is recycled if replaced. */
    private fun cropTo(src: Bitmap, w: Int, h: Int, outW: Int, outH: Int): Bitmap {
        val (cw, ch) = cropSize(src.width, src.height, w, h)
        val m = Matrix().apply { setScale(maxOf(1, outW) / cw.toFloat(), maxOf(1, outH) / ch.toFloat()) }
        val out = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch, m, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun infoOf(bitmap: Bitmap, background: Int): BackdropInfo {
        val w = bitmap.width
        val h = bitmap.height
        val detail = BackdropDetail.of(w, h) { y, into ->
            bitmap.getPixels(into, 0, w, 0, y, w, 1)
            for (i in into.indices) into[i] = KioskScrim.over(into[i], background)
        }
        val (sw, sh) = sampleGrid(w, h)
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val samples = IntArray(sw * sh)
        small.getPixels(samples, 0, sw, 0, 0, sw, sh)
        if (small !== bitmap) small.recycle()
        for (i in samples.indices) samples[i] = KioskScrim.over(samples[i], background)
        val top = samples.copyOfRange(0, sw).sortedBy { Contrast.luminance(it) }[sw / 2]
        return BackdropInfo(background, samples, top, detail, w, h)
    }

    /** 48 along the long edge, `round(48 * short / long)` (at least 1) along the short one. */
    fun sampleGrid(w: Int, h: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        val short = maxOf(1, (SAMPLES * minOf(w, h) / long.toFloat()).roundToInt())
        return if (w >= h) SAMPLES to short else short to SAMPLES
    }

    /** Rotation (and mirroring) from a JPEG's EXIF orientation ([JpegOrientation]); none for other formats. */
    private fun exifOrientation(file: File, mime: String?): Orientation =
        if (mime == "image/jpeg") Orientation.of(JpegOrientation.read(file)) else Orientation.NORMAL

    private const val SAMPLES = 48
    private const val HALF_TURN = 180

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
