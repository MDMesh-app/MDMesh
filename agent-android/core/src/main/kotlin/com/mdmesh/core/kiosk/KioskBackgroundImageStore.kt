package com.mdmesh.core.kiosk

import android.content.Context
import android.graphics.BitmapFactory
import com.mdmesh.core.di.DownloadHttpClient
import com.mdmesh.proto.KioskBackgroundImageDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Retrieves a server-managed kiosk image into private app storage. Every failure (network, checksum, storage, a
 * cache directory that isn't one) comes back as a result, never as an exception: the launcher keeps its colour
 * and must never crash over an optional image. A failed image is tried again only after a backoff that grows per
 * attempt (30 s doubling to 6 h, ±20 %), remembered per image hash, so screen wakes don't re-download it.
 */
@Singleton
class KioskBackgroundImageStore internal constructor(
    private val dir: () -> File,
    private val fetch: (url: String) -> InputStream?,
    private val bounds: (File) -> Pair<Int, Int>?,
    private val failures: FailureMemory,
    private val now: () -> Long,
    /** A number in [-1, 1] that spreads retries (jitter). */
    private val jitter: () -> Double,
) {
    @Inject constructor(@ApplicationContext context: Context, @DownloadHttpClient http: OkHttpClient) : this(
        dir = { File(context.filesDir, DIR) },
        fetch = HttpsFetch(http)::open,
        bounds = ::bitmapBounds,
        failures = PrefsFailureMemory(context),
        now = System::currentTimeMillis,
        jitter = { Random.nextDouble(-1.0, 1.0) },
    )

    /** What [obtain] found. */
    sealed interface Obtained {
        /** The verified image, in the cache. */
        data class Ready(val file: File) : Obtained

        /** Not now; try again at [atMs] (wall clock). */
        data class Retry(val atMs: Long) : Obtained

        /** The descriptor can never work (not HTTPS, no valid hash): don't retry. */
        data object Invalid : Obtained
    }

    /** The image [image] names, from the cache or downloaded and verified. Off the main thread; never throws. */
    suspend fun obtain(image: KioskBackgroundImageDto): Obtained = withContext(Dispatchers.IO) {
        val request = validRequest(image) ?: return@withContext Obtained.Invalid
        runCatching { obtainOrRetry(request) }.getOrElse {
            if (it is CancellationException) throw it
            failed(request.hash)
        }
    }

    private fun obtainOrRetry(request: BackgroundRequest): Obtained {
        val dir = dir()
        val target = File(dir, "${request.hash}.image")
        val backoff = failures.get(request.hash)?.second?.takeIf { now() < it }
        return when {
            !dir.isDirectory && !dir.mkdirs() -> failed(request.hash)
            validImage(target) -> ready(dir, target)
            backoff != null -> Obtained.Retry(backoff)
            else -> {
                target.delete()
                download(request, target)?.let { ready(dir, it) } ?: failed(request.hash)
            }
        }
    }

    private fun ready(dir: File, file: File): Obtained {
        failures.clear()
        // Only the current image is kept: an older one (up to 10 MB each) would stay in private storage forever.
        pruneBackgroundCache(dir, keep = file, nowMs = now())
        return Obtained.Ready(file)
    }

    /** Remembers one more failure for [hash] and when to try it next. A different hash starts afresh. */
    private fun failed(hash: String): Obtained {
        val count = (failures.get(hash)?.first ?: 0) + 1
        val base = min(FIRST_RETRY_MS * (1L shl min(count - 1, MAX_DOUBLINGS)), MAX_RETRY_MS)
        val next = now() + (base * (1 + JITTER * jitter())).roundToLong()
        failures.record(hash, count, next)
        return Obtained.Retry(next)
    }

    private fun validRequest(image: KioskBackgroundImageDto): BackgroundRequest? {
        val url = image.url.trim()
        val hash = image.sha256.trim().lowercase()
        return if (url.startsWith("https://", ignoreCase = true) && HASH.matches(hash)) {
            BackgroundRequest(url, hash)
        } else {
            null
        }
    }

    private fun download(request: BackgroundRequest, target: File): File? {
        // Each request gets a private staging name: launchers can overlap (a recreate, a re-apply), and a shared
        // temporary file would let one request truncate another's verified bytes. Created inside the guard: a full
        // disk or a cache path that isn't a directory is a failed download, not a crash.
        var temporary: File? = null
        return try {
            temporary = File.createTempFile(".${request.hash}.", ".download", target.parentFile)
            val digest = fetch(request.url)?.let { copyAndDigest(it, temporary) }
            if (digest == request.hash && validImage(temporary) && temporary.renameTo(target)) target else null
        } finally {
            temporary?.delete()
        }
    }

    private fun copyAndDigest(input: InputStream, outputFile: File): String? = input.use { source ->
        FileOutputStream(outputFile).use { output ->
            val digest = MessageDigest.getInstance("SHA-256")
            if (copyBounded(source, output, digest)) digest.digest().joinToString("") { "%02x".format(it) } else null
        }
    }

    private fun copyBounded(input: InputStream, output: FileOutputStream, digest: MessageDigest): Boolean {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return false
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return true
    }

    private fun validImage(file: File): Boolean {
        val size = if (file.isFile && file.length() in 1..MAX_BYTES) bounds(file) else null
        return size != null && size.first > 0 && size.second > 0 &&
            size.first.toLong() * size.second.toLong() <= MAX_PIXELS
    }

    /** Failures per image hash: how many in a row and when to try again. Only the latest hash is kept. */
    interface FailureMemory {
        fun get(hash: String): Pair<Int, Long>?
        fun record(hash: String, count: Int, nextAttemptMs: Long)
        fun clear()
    }

    private class PrefsFailureMemory(context: Context) : FailureMemory {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun get(hash: String): Pair<Int, Long>? =
            if (prefs.getString(KEY_HASH, null) == hash) {
                prefs.getInt(KEY_COUNT, 0) to prefs.getLong(KEY_NEXT, 0L)
            } else {
                null
            }

        override fun record(hash: String, count: Int, nextAttemptMs: Long) {
            prefs.edit().putString(KEY_HASH, hash).putInt(KEY_COUNT, count).putLong(KEY_NEXT, nextAttemptMs).apply()
        }

        override fun clear() {
            prefs.edit().clear().apply()
        }
    }

    /** HTTPS only: no switch to HTTP on a redirect. The response closes with the stream. */
    private class HttpsFetch(http: OkHttpClient) {
        private val client: OkHttpClient by lazy { http.newBuilder().followSslRedirects(false).build() }

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
        data class BackgroundRequest(val url: String, val hash: String)

        const val DIR = "kiosk-background"
        private const val PREFS = "mdm_kiosk_background"
        private const val KEY_HASH = "failed_hash"
        private const val KEY_COUNT = "failures"
        private const val KEY_NEXT = "next_attempt_ms"
        val HASH = Regex("[0-9a-f]{64}")
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_PIXELS = 16L * 1024 * 1024
        const val FIRST_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 6L * 60 * 60 * 1000
        const val MAX_DOUBLINGS = 20
        const val JITTER = 0.2

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
