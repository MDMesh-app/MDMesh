package com.mdmesh.core.kiosk

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.mdmesh.core.di.DownloadHttpClient
import com.mdmesh.proto.KioskBackgroundImageDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.mdmesh.core.store.KioskStateStore
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Retrieves a server-managed kiosk image into private app storage. Every failure (network, checksum, a full disk, a
 * cache directory that isn't one or was cleared) comes back as a result, never as an exception: the launcher keeps
 * what it shows and must never crash over an optional image.
 *
 * - A failed download is tried again only after a backoff that grows per attempt (30 s doubling to 6 h, ±20 %),
 *   per image hash, so screen wakes don't re-download it. The count survives a restart; the waiting runs on the
 *   elapsed-time clock (a changed wall clock neither stalls nor rushes it), so a new process may try once at once.
 * - A download is written to a private temporary file and only renamed into place once its SHA-256 matches: a
 *   process killed midway leaves no file that is ever used (old leftovers are pruned).
 * - A cached file is checked against its hash once per process before it is first used: a corrupt or truncated one
 *   is deleted and downloaded again.
 * - An image that matches its hash but can't be decoded ([undecodable]) is remembered as [Obtained.Invalid]: no
 *   retries, ever, for that hash.
 * - The last image that was ready ([lastGood]) stays available while a new one is not, e.g. deleted on the server.
 * - One download per image at a time, whoever asks (the launcher, the prefetch worker): a second request waits for
 *   the first and takes its result (the cached file, or its backoff), so an image is fetched and a failure counted
 *   once.
 * - Only the kiosk's current image ([isCurrent]) prunes the cache and becomes the last good one: a download of an
 *   image replaced meanwhile that completes late changes neither.
 * - A download is bounded in time as a whole ([DOWNLOAD_TIMEOUT_MS]), not only per read.
 */
@Singleton
class KioskBackgroundImageStore internal constructor(
    private val dir: () -> File,
    private val fetch: (url: String) -> InputStream?,
    private val bounds: (File) -> Pair<Int, Int>?,
    private val failures: FailureMemory,
    private val clocks: Clocks,
    /** Whether an image hash is the one the saved kiosk shows now (production: the kiosk state). */
    private val isCurrent: suspend (hash: String) -> Boolean,
) {
    /** Time and chance, injected for tests. */
    class Clocks(
        /** Monotonic milliseconds (SystemClock.elapsedRealtime): the backoff's clock. */
        val elapsed: () -> Long,
        /** Wall-clock milliseconds, only to age leftover downloads by their file time. */
        val wallNow: () -> Long,
        /** A number in [-1, 1] that spreads retries (jitter). */
        val jitter: () -> Double,
    )

    @Inject constructor(
        @ApplicationContext context: Context,
        @DownloadHttpClient http: OkHttpClient,
        kiosk: KioskStateStore,
    ) : this(
        dir = { File(context.filesDir, DIR) },
        fetch = HttpsFetch(http)::open,
        bounds = ::bitmapBounds,
        failures = PrefsFailureMemory(context),
        clocks = Clocks(SystemClock::elapsedRealtime, System::currentTimeMillis) { Random.nextDouble(-1.0, 1.0) },
        isCurrent = { hash -> kiosk.load()?.theme?.backgroundImage?.sha256?.trim()?.lowercase() == hash },
    )

    /** What [obtain] found. */
    sealed interface Obtained {
        /** The verified image, in the cache. */
        data class Ready(val file: File) : Obtained

        /** Not now; try again in [inMs]. */
        data class Retry(val inMs: Long) : Obtained

        /** Can never work (not HTTPS, no valid hash, or the verified image doesn't decode): don't retry. */
        data object Invalid : Obtained
    }

    /** When each hash may be downloaded again, on the [elapsed] clock. In memory only: see the class notes. */
    private val notBefore = ConcurrentHashMap<String, Long>()

    /** Cached files whose hash was checked in this process, by name, size and time. */
    private val checked = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** One lock per image hash: one download (or cache check) of an image at a time. A few hashes per process. */
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    /** The current image whose readiness is already recorded (prefs written, cache pruned) in this process. */
    @Volatile private var noted: String? = null

    /**
     * The image [image] names, from the cache or downloaded and verified. Off the main thread; never throws. A request
     * for an image already being fetched waits for that download and shares its outcome.
     */
    suspend fun obtain(image: KioskBackgroundImageDto): Obtained = guarded(image) { request ->
        inFlight.getOrPut(request.hash) { Mutex() }.withLock { obtainOrRetry(request) }
    }

    /**
     * The launcher could not decode [image]'s file. If the file still matches its hash, the image itself is bad:
     * remembered as [Obtained.Invalid]. If not (corrupt, truncated, or gone), the copy is dropped and fetched again.
     */
    suspend fun undecodable(image: KioskBackgroundImageDto): Obtained = guarded(image) { request ->
        val target = File(dir(), "${request.hash}.image")
        checked.remove(target.name)
        if (noted == request.hash) noted = null
        if (target.isFile && sha256(target) == request.hash) {
            failures.markInvalid(request.hash)
            Obtained.Invalid
        } else {
            target.delete()
            Obtained.Retry(0)
        }
    }

    /** The last image that was ready, while its file is still there; null otherwise. Off the main thread. */
    suspend fun lastGood(): File? = withContext(Dispatchers.IO) {
        runCatching {
            failures.lastGood()?.let { File(dir(), "$it.image") }?.takeIf { it.isFile }
        }.getOrNull()
    }

    private suspend fun guarded(
        image: KioskBackgroundImageDto,
        block: suspend (BackgroundRequest) -> Obtained,
    ): Obtained =
        withContext(Dispatchers.IO) {
            val request = validRequest(image) ?: return@withContext Obtained.Invalid
            runCatching { block(request) }.getOrElse {
                if (it is CancellationException) throw it
                failed(request)
            }
        }

    private suspend fun obtainOrRetry(request: BackgroundRequest): Obtained {
        val dir = dir()
        val target = File(dir, "${request.hash}.image")
        val wait = (notBefore[request.source] ?: 0L) - clocks.elapsed()
        return when {
            failures.isInvalid(request.hash) -> Obtained.Invalid
            !dir.isDirectory && !dir.mkdirs() -> failed(request)
            cachedAndIntact(target, request.hash) -> ready(dir, target, request.hash)
            wait > 0 -> Obtained.Retry(wait)
            else -> {
                target.delete()
                when (val got = download(request, target)) {
                    Download.Done -> ready(dir, target, request.hash)
                    Download.Failed -> failed(request)
                    Download.NeverValid -> {
                        // The verified file is the image the server names, and it is over the caps (or has no size):
                        // it can never be used, so it isn't fetched again either.
                        failures.markInvalid(request.hash)
                        Obtained.Invalid
                    }
                }
            }
        }
    }

    /** [target] is there, a plausible image, and (once per process) matches [hash]; a corrupt copy is deleted. */
    private fun cachedAndIntact(target: File, hash: String): Boolean {
        if (!validImage(target)) return false
        val stamp = target.length() to target.lastModified()
        val intact = checked[target.name] == stamp || sha256(target) == hash
        if (intact) checked[target.name] = stamp else target.delete()
        return intact
    }

    private suspend fun ready(dir: File, file: File, hash: String): Obtained {
        notBefore.keys.removeAll { it.startsWith(hash) }
        checked[file.name] = file.length() to file.lastModified()
        // Recorded once per process for the current image (a cache hit is every screen-on: no prefs write, no
        // directory scan each time). An image replaced meanwhile (a download that completed late) records nothing:
        // it would prune the current image and come back as the stand-in while that can't be had.
        if (noted != hash && isCurrent(hash)) {
            failures.succeeded(hash)
            // Only the current image is kept: an older one (up to 10 MB each) would stay in private storage forever.
            pruneBackgroundCache(dir, keep = file, nowMs = clocks.wallNow())
            noted = hash
        }
        return Obtained.Ready(file)
    }

    /**
     * Remembers one more failure for [request]'s image at its URL, and when to try that again. Another image, or the
     * same one at another URL (a server that moved), starts afresh: one dead address never holds back a live one,
     * whichever of the launcher or the prefetch worker asks.
     */
    private fun failed(request: BackgroundRequest): Obtained {
        // A failure since it was noted ready (its file gone, fetched again): the next success records itself again.
        if (noted == request.hash) noted = null
        val key = request.source
        val count = failures.failures(key) + 1
        val base = min(FIRST_RETRY_MS * (1L shl min(count - 1, MAX_DOUBLINGS)), MAX_RETRY_MS)
        val wait = (base * (1 + JITTER * clocks.jitter())).roundToLong()
        failures.failed(key, count)
        notBefore[key] = clocks.elapsed() + wait
        return Obtained.Retry(wait)
    }

    private enum class Download { Done, Failed, NeverValid }

    private fun download(request: BackgroundRequest, target: File): Download {
        // Each download gets a private staging name (a temporary file a killed process left is never used), created
        // inside the guard: a full disk or a cache path that isn't a directory is a failed download, not a crash.
        var temporary: File? = null
        return try {
            temporary = File.createTempFile(".${request.hash}.", ".download", target.parentFile)
            val digest = fetch(request.url)?.let { copyAndDigest(it, temporary) }
            when {
                digest != request.hash -> Download.Failed
                !validImage(temporary) -> Download.NeverValid
                temporary.renameTo(target) -> Download.Done
                else -> Download.Failed
            }
        } finally {
            temporary?.delete()
        }
    }

    private fun validImage(file: File): Boolean {
        val size = if (file.isFile && file.length() in 1..MAX_BYTES) bounds(file) else null
        return size != null && size.first > 0 && size.second > 0 &&
            size.first.toLong() * size.second.toLong() <= MAX_PIXELS
    }

    /**
     * What survives a restart: the failures in a row for the latest failing source (an image hash at a URL), the
     * hashes that never decode, and the last hash that was ready.
     */
    interface FailureMemory {
        fun failures(source: String): Int
        fun failed(source: String, count: Int)
        fun succeeded(hash: String)
        fun isInvalid(hash: String): Boolean
        fun markInvalid(hash: String)
        fun lastGood(): String?
    }

    private class PrefsFailureMemory(context: Context) : FailureMemory {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun failures(source: String): Int =
            if (prefs.getString(KEY_SOURCE, null) == source) prefs.getInt(KEY_COUNT, 0) else 0

        override fun failed(source: String, count: Int) {
            prefs.edit().putString(KEY_SOURCE, source).putInt(KEY_COUNT, count).apply()
        }

        override fun succeeded(hash: String) {
            prefs.edit().remove(KEY_SOURCE).remove(KEY_COUNT).putString(KEY_LAST_GOOD, hash).apply()
        }

        override fun isInvalid(hash: String): Boolean = hash in prefs.getStringSet(KEY_INVALID, emptySet()).orEmpty()

        override fun markInvalid(hash: String) {
            // A few hashes at most: an admin replaces a broken image. Bounded all the same.
            val kept = prefs.getStringSet(KEY_INVALID, emptySet()).orEmpty().take(MAX_INVALID - 1).toSet()
            prefs.edit().putStringSet(KEY_INVALID, kept + hash).apply()
        }

        override fun lastGood(): String? = prefs.getString(KEY_LAST_GOOD, null)
    }

    /** HTTPS only: no switch to HTTP on a redirect. The response closes with the stream. */
    private class HttpsFetch(http: OkHttpClient) {
        private val client: OkHttpClient by lazy { downloadClient(http) }

        fun open(url: String): InputStream? {
            val response = client.newCall(Request.Builder().url(url).get().build()).execute()
            val body = response.body
            if (!response.isSuccessful || body == null) {
                response.close()
                return null
            }
            return body.byteStream()
        }
    }

    internal companion object {
        data class BackgroundRequest(val url: String, val hash: String) {
            /** What a backoff is for: this image at this address. */
            val source: String get() = "$hash $url"
        }

        const val DIR = "kiosk-background"
        private const val PREFS = "mdm_kiosk_background"
        /** The failing source, "<sha256> <url>" (it was the hash alone before; such an entry reads as no failures). */
        private const val KEY_SOURCE = "failed_source"
        private const val KEY_COUNT = "failures"
        private const val KEY_INVALID = "invalid_hashes"
        private const val KEY_LAST_GOOD = "last_good"
        private const val MAX_INVALID = 8
        val HASH = Regex("[0-9a-f]{64}")
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_PIXELS = 16L * 1024 * 1024
        const val FIRST_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 6L * 60 * 60 * 1000
        const val MAX_DOUBLINGS = 20
        const val JITTER = 0.2

        /**
         * A whole download, connect to last byte: 10 MB in 10 minutes is 17 KB/s, below which a link is too poor
         * anyway. Per-read timeouts alone let a server trickling a byte every few seconds hold a thread for ever.
         */
        const val DOWNLOAD_TIMEOUT_MS = 10L * 60 * 1000

        /** The image download's client: [http] with no switch from HTTPS on a redirect, and an overall time limit. */
        fun downloadClient(http: OkHttpClient): OkHttpClient = http.newBuilder()
            .followSslRedirects(false)
            .callTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun bitmapBounds(file: File): Pair<Int, Int>? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            return (options.outWidth to options.outHeight).takeIf { it.first > 0 && it.second > 0 }
        }
    }
}

/**
 * Deletes every cached image in [dir] but [keep], and downloads left behind (a killed process) once they are older
 * than [STALE_DOWNLOAD_MS]; a younger one may still be written by another launcher instance.
 */
internal fun pruneBackgroundCache(dir: File, keep: File, nowMs: Long) {
    dir.listFiles()?.forEach { file ->
        val stale = when {
            file.name == keep.name -> false
            file.name.endsWith(".image") -> true
            file.name.endsWith(".download") -> nowMs - file.lastModified() > STALE_DOWNLOAD_MS
            else -> false
        }
        if (stale && file.isFile) file.delete()
    }
}

/** A download takes seconds; one this old was abandoned. */
internal const val STALE_DOWNLOAD_MS = 60L * 60 * 1000

private fun validRequest(image: KioskBackgroundImageDto): KioskBackgroundImageStore.Companion.BackgroundRequest? {
    val url = image.url.trim()
    val hash = image.sha256.trim().lowercase()
    return if (url.startsWith("https://", ignoreCase = true) && KioskBackgroundImageStore.HASH.matches(hash)) {
        KioskBackgroundImageStore.Companion.BackgroundRequest(url, hash)
    } else {
        null
    }
}

private fun copyAndDigest(input: InputStream, outputFile: File): String? = input.use { source ->
    FileOutputStream(outputFile).use { output ->
        val digest = MessageDigest.getInstance("SHA-256")
        if (copyBounded(source, output, digest)) digest.digest().joinToString("") { "%02x".format(it) } else null
    }
}

private fun copyBounded(input: InputStream, output: FileOutputStream, digest: MessageDigest): Boolean {
    val buffer = ByteArray(KioskBackgroundImageStore.BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > KioskBackgroundImageStore.MAX_BYTES) return false
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
    }
    return true
}
