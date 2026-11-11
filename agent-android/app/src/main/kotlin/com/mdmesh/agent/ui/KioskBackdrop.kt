package com.mdmesh.agent.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import com.mdmesh.core.kiosk.KioskBackgroundImageStore
import com.mdmesh.core.kiosk.KioskBackgroundImageStore.Obtained
import com.mdmesh.proto.KioskBackgroundImageDto
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The kiosk launcher's background image: fetched and decoded off the main thread, once per image and window size,
 * handed to [onReady]. It never touches the kiosk state: an image that arrives only repaints the backdrop (no
 * pinned app reopened, no hold cleared). The image shown stays until a new one is ready (or the kiosk ends).
 * A failed fetch is tried again when the store's backoff says, and on every [want] (each kiosk state the launcher
 * gets: back at the front, the screen on).
 */
class KioskBackdrop(
    private val scope: CoroutineScope,
    private val store: KioskBackgroundImageStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val onReady: (Bitmap?, BackdropStats?) -> Unit,
) {
    private var wanted: KioskBackgroundImageDto? = null
    private var size = 0 to 0
    private var shown: Pair<String, Pair<Int, Int>>? = null
    private var job: Job? = null

    /** The job is only waiting out a retry delay: a new [want] (HOME back at the front) may look again at once. */
    private var waiting = false

    /** Show [image] (by its hash) for a window [width] x [height]; null: no image. */
    fun want(image: KioskBackgroundImageDto?, width: Int, height: Int) {
        if (image == null) return clear()
        val sameImage = wanted?.sha256.equals(image.sha256, ignoreCase = true)
        wanted = image
        size = width to height
        val alreadyShown = shown == image.sha256.lowercase() to size
        // Busy fetching or decoding this image: let it finish. Only waiting for a retry: look again now (a cached
        // image shows at once; the store's backoff still stops a re-download).
        val busy = job?.isActive == true && !waiting
        if (sameImage && (alreadyShown || busy)) return
        job?.cancel()
        job = scope.launch { load(image, width, height) }
    }

    /** No image: back to the plain colour. */
    fun clear() {
        job?.cancel()
        job = null
        wanted = null
        if (shown != null) {
            shown = null
            onReady(null, null)
        }
    }

    private suspend fun load(image: KioskBackgroundImageDto, width: Int, height: Int) {
        var got = store.obtain(image)
        while (got is Obtained.Retry) {
            waiting = true
            try {
                delay((got.atMs - now()).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))
            } finally {
                waiting = false
            }
            got = store.obtain(image)
        }
        val file = (got as? Obtained.Ready)?.file ?: return // Invalid: never works
        val decoded = withContext(Dispatchers.Default) { decode(file, width, height) } ?: return
        shown = image.sha256.lowercase() to (width to height)
        onReady(decoded.first, decoded.second)
    }

    companion object {
        private const val MIN_WAIT_MS = 1_000L
        private const val MAX_WAIT_MS = 6L * 60 * 60 * 1000
        private const val STATS_SIZE = 48
        private const val TOP_ROWS = 4

        /**
         * [file] decoded to cover a [width] x [height] window: upright by its EXIF orientation, subsampled by powers
         * of two while it still covers the window, RGB_565 for a JPEG (opaque), and on running out of memory once
         * more halved before giving up. Null when it can't be decoded. Plus its [BackdropStats].
         */
        fun decode(file: File, width: Int, height: Int): Pair<Bitmap, BackdropStats>? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val rotation = exifRotation(file)
            val sideways = rotation.degrees % HALF_TURN != 0
            val (iw, ih) = if (sideways) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
            var sample = coverSample(iw, ih, width.coerceAtLeast(1), height.coerceAtLeast(1))
            val opaque = bounds.outMimeType == "image/jpeg"
            var bitmap: Bitmap? = null
            repeat(DECODE_TRIES) {
                if (bitmap == null) bitmap = decodeSampled(file, sample, opaque)
                if (bitmap == null) sample *= 2
            }
            val upright = rotation.apply(bitmap ?: return@runCatching null)
            upright to statsOf(upright, width, height)
        }.getOrNull()

        /** The largest power-of-two subsample of an [iw] x [ih] image that still covers [w] x [h]. */
        fun coverSample(iw: Int, ih: Int, w: Int, h: Int): Int {
            var sample = 1
            while (iw / (sample * 2) >= w && ih / (sample * 2) >= h) sample *= 2
            return sample
        }

        private fun decodeSampled(file: File, sample: Int, opaque: Boolean): Bitmap? = try {
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    if (opaque) inPreferredConfig = Bitmap.Config.RGB_565
                },
            )
        } catch (_: OutOfMemoryError) {
            null
        }

        /** Rotation (and mirroring) from the file's EXIF orientation; none when it has none or can't be read. */
        private fun exifRotation(file: File): Orientation = runCatching {
            val exif = ExifInterface(file.path)
            Orientation.of(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        }.getOrDefault(Orientation.NORMAL)

        /** Stats of the part of [bitmap] a [width] x [height] window shows (centre crop), downsampled. */
        private fun statsOf(bitmap: Bitmap, width: Int, height: Int): BackdropStats {
            val scale = maxOf(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
            val cw = (width / scale).toInt().coerceIn(1, bitmap.width)
            val ch = (height / scale).toInt().coerceIn(1, bitmap.height)
            val crop = Rect((bitmap.width - cw) / 2, (bitmap.height - ch) / 2, 0, 0).apply {
                right = left + cw
                bottom = top + ch
            }
            val part = Bitmap.createBitmap(bitmap, crop.left, crop.top, cw, ch)
            val small = Bitmap.createScaledBitmap(part, STATS_SIZE, STATS_SIZE, true)
            val pixels = IntArray(STATS_SIZE * STATS_SIZE)
            small.getPixels(pixels, 0, STATS_SIZE, 0, 0, STATS_SIZE, STATS_SIZE)
            if (small !== part) small.recycle()
            if (part !== bitmap) part.recycle()
            return KioskScrim.stats(pixels, STATS_SIZE, TOP_ROWS)
        }

        private const val HALF_TURN = 180
        /** Once as sized, once more halved after running out of memory. */
        private const val DECODE_TRIES = 2
    }

    /** An EXIF orientation as a rotation plus an optional mirror. */
    enum class Orientation(val degrees: Int, private val mirror: Boolean) {
        NORMAL(NONE, false),
        MIRROR(NONE, true),
        ROTATE_180(HALF, false),
        FLIP(HALF, true),
        TRANSPOSE(QUARTER, true),
        ROTATE_90(QUARTER, false),
        TRANSVERSE(THREE_QUARTERS, true),
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
            /** From ExifInterface.TAG_ORIENTATION (1..8). */
            fun of(exif: Int): Orientation = when (exif) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> MIRROR
                ExifInterface.ORIENTATION_ROTATE_180 -> ROTATE_180
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> FLIP
                ExifInterface.ORIENTATION_TRANSPOSE -> TRANSPOSE
                ExifInterface.ORIENTATION_ROTATE_90 -> ROTATE_90
                ExifInterface.ORIENTATION_TRANSVERSE -> TRANSVERSE
                ExifInterface.ORIENTATION_ROTATE_270 -> ROTATE_270
                else -> NORMAL
            }
        }
    }
}

private const val NONE = 0
private const val QUARTER = 90
private const val HALF = 180
private const val THREE_QUARTERS = 270
