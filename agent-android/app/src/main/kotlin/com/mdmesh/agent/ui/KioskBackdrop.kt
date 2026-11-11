package com.mdmesh.agent.ui

import android.graphics.Bitmap
import com.mdmesh.core.kiosk.KioskBackgroundImageStore
import com.mdmesh.core.kiosk.KioskBackgroundImageStore.Obtained
import com.mdmesh.proto.KioskBackgroundImageDto
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The kiosk launcher's background image: fetched and decoded off the main thread, once per image (by its SHA-256
 * alone: a new URL for the same file changes nothing), window size and background colour, and handed to [onReady].
 * It never touches the kiosk state: an image that arrives only repaints the backdrop (no pinned app reopened, no
 * hold cleared).
 *
 * The image shown stays until a new one is ready, or the kiosk ends ([clear]). While a new image can't be had (not
 * downloaded yet, deleted on the server, undecodable) and nothing is shown, the last good one is shown. A failed
 * fetch is tried again when the store's backoff says, and on every [want] (each kiosk state the launcher gets: back
 * at the front, the screen on). A bitmap that was handed out is never recycled here: the drawable that draws it
 * may still be on screen, and the garbage collector frees it once nothing draws it.
 */
class KioskBackdrop(
    private val scope: CoroutineScope,
    private val store: KioskBackgroundImageStore,
    private val limits: DecodeLimits,
    private val decoding: CoroutineDispatcher = Dispatchers.Default,
    private val onReady: (Bitmap?, BackdropInfo?) -> Unit,
) {
    private data class Key(val sha256: String, val screenLong: Int, val background: Int)

    private var wanted: Key? = null
    private var wantedUrl: String? = null
    private var shown: Key? = null

    /** What is shown has no transparency: it holds over any background colour, a recolour needs no new decode. */
    private var shownOpaque = false

    /** The last good image stands in (for a different key): replaced when the wanted one is ready. */
    private var standIn: Key? = null
    private var job: Job? = null

    /** The job is only waiting out a retry delay: a new [want] (HOME back at the front) may look again at once. */
    private var waiting = false

    /**
     * Show [image] on a screen whose long edge is [screenLong] px, over [background]; null: no image. Its fit and
     * focal point and the window's shape (rotation) need no new decode: the renderer places the same bitmap.
     */
    fun want(image: KioskBackgroundImageDto?, screenLong: Int, background: Int) {
        if (image == null) return clear()
        val key = Key(image.sha256.trim().lowercase(), screenLong, background)
        val same = wanted == key
        // A new URL for the same image (a server that moved): fetch from there from now on.
        val moved = image.url != wantedUrl
        wanted = key
        wantedUrl = image.url
        // Shown already (an opaque image serves any colour). Or busy fetching or decoding this image: let it finish.
        // Only waiting for a retry: look again now (a cached image shows at once; the store's backoff still stops a
        // re-download).
        val showing = shown?.let { covers(it, key) } == true
        val busy = job?.isActive == true && !waiting
        val inHand = showing || (same && busy && !moved)
        if (!inHand) {
            job?.cancel()
            job = scope.launch { load(image, key) }
        }
    }

    /** [shown] is a decode that serves [key]: the same image and screen, over its colour or any (opaque). */
    private fun covers(shown: Key, key: Key): Boolean =
        shown.sha256 == key.sha256 && shown.screenLong == key.screenLong &&
            (shown.background == key.background || shownOpaque)

    /** No image: back to the plain colour. */
    fun clear() {
        job?.cancel()
        job = null
        wanted = null
        if (shown != null || standIn != null) {
            shown = null
            standIn = null
            onReady(null, null)
        }
    }

    private suspend fun load(image: KioskBackgroundImageDto, key: Key) {
        var got: Obtained? = store.obtain(image)
        while (got != null) {
            got = when (val now: Obtained = got) {
                is Obtained.Ready -> when (val decoded = decode(now.file, key)) {
                    is Decoded.Ready -> null.also { show(key, decoded) }
                    Decoded.Undecodable -> store.undecodable(image)
                    Decoded.OutOfMemory -> null // keep what is shown; the next want tries again
                }
                is Obtained.Retry -> {
                    showLastGood(key)
                    waiting = true
                    try {
                        delay(now.inMs.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))
                    } finally {
                        waiting = false
                    }
                    store.obtain(image)
                }
                Obtained.Invalid -> null.also { showLastGood(key) }
            }
        }
    }

    /** Nothing shown yet: the last image that was ready, if any, until this one is. */
    private suspend fun showLastGood(key: Key) {
        if (shown != null || standIn == key) return
        val file = store.lastGood()?.takeUnless { it.name.startsWith(key.sha256) } ?: return
        (decode(file, key) as? Decoded.Ready)?.let {
            standIn = key
            onReady(it.bitmap, it.info)
        }
    }

    private fun show(key: Key, decoded: Decoded.Ready) {
        shown = key
        shownOpaque = decoded.info.opaque
        standIn = null
        onReady(decoded.bitmap, decoded.info)
    }

    /**
     * One decode at a time, in the whole process: a decode can't be interrupted, so a superseded one (a quick
     * recolour) finishes before the next starts, rather than two full-size bitmaps at once.
     */
    private suspend fun decode(file: File, key: Key): Decoded = decodes.withLock {
        withContext(decoding) { BackdropDecoder.decode(file, key.screenLong, key.background, limits) }
    }

    private companion object {
        val decodes = Mutex()

        const val MIN_WAIT_MS = 1_000L
        const val MAX_WAIT_MS = 6L * 60 * 60 * 1000
    }
}
