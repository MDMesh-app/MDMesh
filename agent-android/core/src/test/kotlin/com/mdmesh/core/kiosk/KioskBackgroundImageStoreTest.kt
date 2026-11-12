package com.mdmesh.core.kiosk

import com.mdmesh.core.kiosk.KioskBackgroundImageStore.Obtained
import com.mdmesh.proto.KioskBackgroundImageDto
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The kiosk image store: verified downloads, a cache by hash, and failures that never throw and back off. */
class KioskBackgroundImageStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private val bytes = "an image".toByteArray()
    private val hash = sha(bytes)
    private val image = KioskBackgroundImageDto("https://mdm.example.com/files/kiosk.png", hash)
    private var nowMs = 1_000_000L
    private var wallMs = 1_700_000_000_000L
    private var fetches = 0
    private var serve: () -> InputStream? = { ByteArrayInputStream(bytes) }
    /** The kiosk's image now (null: no kiosk or no image); null here means every image is the current one. */
    private var current: String? = null
    /** The pixel size a file's header gives. */
    private var size: Pair<Int, Int> = 100 to 100
    private val memory = MemFailures()
    private lateinit var dir: File

    private class MemFailures : KioskBackgroundImageStore.FailureMemory {
        var entry: Pair<String, Int>? = null
        val invalid = mutableSetOf<String>()
        var good: String? = null
        /** Every write, in order: what a cache hit costs in prefs commits. */
        val writes = mutableListOf<String>()
        override fun failures(source: String) = entry?.takeIf { it.first == source }?.second ?: 0
        override fun failed(source: String, count: Int) { writes += "failed $count"; entry = source to count }
        override fun succeeded(hash: String) { writes += "succeeded"; entry = null; good = hash }
        override fun isInvalid(hash: String) = hash in invalid
        override fun markInvalid(hash: String) { invalid += hash }
        override fun lastGood() = good
    }

    private fun store(
        dirFile: File = File(temp.root, "kiosk-background").also { dir = it },
    ) = KioskBackgroundImageStore(
        dir = { dirFile },
        fetch = { fetches++; serve() },
        bounds = { f -> if (f.length() > 0) size else null },
        failures = memory,
        clocks = KioskBackgroundImageStore.Clocks(elapsed = { nowMs }, wallNow = { wallMs }, jitter = { 0.0 }),
        isCurrent = { h -> current == null || current == h },
    )

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun obtain(s: KioskBackgroundImageStore = store()) = runBlocking { s.obtain(image) }

    @Test fun `a verified download lands in the cache by hash`() {
        val got = obtain()
        assertEquals(Obtained.Ready(File(dir, "$hash.image")), got)
        assertEquals(listOf("$hash.image"), dir.list()!!.toList())
    }

    @Test fun `a cached image needs no download`() {
        val s = store()
        obtain(s)
        obtain(s)
        assertEquals(1, fetches)
    }

    @Test fun `a checksum mismatch is a failure, remembered, with no file left`() {
        serve = { ByteArrayInputStream("something else".toByteArray()) }
        val s = store()
        val got = obtain(s)
        assertTrue(got is Obtained.Retry)
        assertTrue(dir.list()!!.isEmpty())
        // Woken again before the backoff: no new download (no 10 MB per screen wake).
        obtain(s)
        assertEquals(1, fetches)
    }

    @Test fun `retries back off 30 s doubling, capped at 6 h`() {
        serve = { null }
        val s = store()
        val waits = (1..12).map {
            val wait = (obtain(s) as Obtained.Retry).inMs
            nowMs += wait
            wait
        }
        assertEquals(30_000L, waits[0])
        assertEquals(60_000L, waits[1])
        assertEquals(120_000L, waits[2])
        assertEquals(6L * 60 * 60 * 1000, waits.last())
    }

    @Test fun `a new image starts afresh, and success forgets the failures`() {
        serve = { null }
        val s = store()
        obtain(s)
        val other = KioskBackgroundImageDto(image.url, sha("other".toByteArray()))
        serve = { ByteArrayInputStream("other".toByteArray()) }
        assertTrue(runBlocking { s.obtain(other) } is Obtained.Ready)
        assertEquals(null, memory.entry)
        assertEquals(other.sha256, memory.good)
    }

    @Test fun `a stream over 10 MB is refused`() {
        serve = { ByteArrayInputStream(ByteArray((10 * 1024 * 1024) + 1)) }
        assertTrue(obtain() is Obtained.Retry)
    }

    @Test fun `only an https URL with a sha-256 is ever fetched`() {
        val s = store()
        assertEquals(Obtained.Invalid, runBlocking { s.obtain(image.copy(url = "http://mdm.example.com/x.png")) })
        assertEquals(Obtained.Invalid, runBlocking { s.obtain(image.copy(sha256 = "abc")) })
        assertEquals(0, fetches)
    }

    @Test fun `a cache path that isn't a directory is a failure, not a crash`() {
        val file = temp.newFile("kiosk-background")
        assertTrue(obtain(store(file)) is Obtained.Retry)
    }

    @Test fun `a network error is a failure, not a crash`() {
        serve = { throw IOException("reset") }
        assertTrue(obtain() is Obtained.Retry)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".download") })
    }

    @Test fun `the backoff runs on elapsed time, a wall clock set back or forward changes nothing`() {
        serve = { null }
        val s = store()
        obtain(s)
        wallMs -= 365L * 24 * 60 * 60 * 1000
        assertTrue(obtain(s) is Obtained.Retry)
        wallMs += 2 * 365L * 24 * 60 * 60 * 1000
        assertTrue(obtain(s) is Obtained.Retry)
        assertEquals("no download before 30 s of elapsed time", 1, fetches)
        nowMs += 30_000
        obtain(s)
        assertEquals(2, fetches)
    }

    @Test fun `a new process may try once at once, and keeps growing the backoff`() {
        serve = { null }
        obtain(store())
        obtain(store()) // a restart: the in-memory wait is gone, the count is not
        assertEquals(2, fetches)
        assertEquals(2, memory.failures("$hash ${image.url}"))
    }

    @Test fun `a corrupt or truncated cached copy is dropped and downloaded again`() {
        val s = store()
        obtain(s)
        File(dir, "$hash.image").writeBytes("an ima".toByteArray()) // truncated, new size
        assertEquals(Obtained.Ready(File(dir, "$hash.image")), obtain(store()))
        assertEquals(2, fetches)
        assertTrue(File(dir, "$hash.image").readBytes().contentEquals(bytes))
    }

    @Test fun `a cache directory cleared while running is created again`() {
        val s = store()
        obtain(s)
        dir.listFiles()!!.forEach { it.delete() }
        dir.delete()
        assertTrue(obtain(s) is Obtained.Ready)
        assertEquals(2, fetches)
    }

    @Test fun `a full disk is a failure with no partial file`() {
        serve = {
            object : InputStream() {
                var n = 0
                override fun read(): Int = if (n++ < 10) 1 else throw IOException("ENOSPC")
            }
        }
        assertTrue(obtain() is Obtained.Retry)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun `a download cut off by process death leaves nothing that is used`() {
        dir = File(temp.root, "kiosk-background").apply { mkdirs() }
        File(dir, ".$hash.123.download").writeBytes(bytes.copyOf(3))
        serve = { null }
        assertTrue("the partial file is not the image", obtain() is Obtained.Retry)
    }

    @Test fun `an image that matches its hash but never decodes is invalid for good`() {
        val s = store()
        obtain(s)
        assertEquals(Obtained.Invalid, runBlocking { s.undecodable(image) })
        assertEquals(Obtained.Invalid, obtain(store()))
        assertEquals("never fetched again", 1, fetches)
    }

    @Test fun `an undecodable copy that no longer matches its hash is fetched again, not marked invalid`() {
        val s = store()
        obtain(s)
        File(dir, "$hash.image").writeBytes("garbage!".toByteArray())
        assertEquals(Obtained.Retry(0), runBlocking { s.undecodable(image) })
        assertTrue(obtain(s) is Obtained.Ready)
        assertEquals(2, fetches)
        assertTrue(memory.invalid.isEmpty())
    }

    @Test fun `an image gone from the server keeps the cached copy and the last good one`() {
        val s = store()
        obtain(s)
        serve = { null } // 404
        assertTrue(obtain(s) is Obtained.Ready)
        val other = KioskBackgroundImageDto(image.url, sha("other".toByteArray()))
        assertTrue(runBlocking { s.obtain(other) } is Obtained.Retry)
        assertEquals(File(dir, "$hash.image"), runBlocking { s.lastGood() })
    }

    @Test fun `a failing old URL never holds back the image's new URL`() {
        val urlsTried = mutableListOf<String>()
        val s = KioskBackgroundImageStore(
            dir = { File(temp.root, "kiosk-background").also { dir = it } },
            fetch = { url -> urlsTried += url; if (url.contains("new")) ByteArrayInputStream(bytes) else null },
            bounds = { f -> if (f.length() > 0) 100 to 100 else null },
            failures = memory,
            clocks = KioskBackgroundImageStore.Clocks(elapsed = { nowMs }, wallNow = { wallMs }, jitter = { 0.0 }),
            isCurrent = { true },
        )
        val old = image.copy(url = "https://old.example/kiosk.png")
        runBlocking { s.obtain(old) } // the launcher, trying the dead URL just now
        val moved = image.copy(url = "https://new.example/kiosk.png")
        assertTrue("the new URL is tried at once", runBlocking { s.obtain(moved) } is Obtained.Ready)
        assertEquals("https://new.example/kiosk.png", urlsTried.last())
    }

    // --- PR #192 final review (S4, N8-N10): one download per image, a late one never undoes the current image ---

    /** A source that blocks until [release], counting the streams opened. */
    private fun gated(release: CountDownLatch, payload: ByteArray = bytes): () -> InputStream? = {
        release.await(10, TimeUnit.SECONDS)
        ByteArrayInputStream(payload)
    }

    /** The launcher and the prefetch worker ask for the same image at once: one download, both get it. */
    @Test fun `concurrent requests for one image download it once`() = runBlocking {
        val release = CountDownLatch(1)
        serve = gated(release)
        val s = store()
        val asks = (1..4).map { async(Dispatchers.IO) { s.obtain(image) } }
        Thread.sleep(200) // every request is in, one is downloading
        release.countDown()
        val got = asks.awaitAll()
        assertEquals("one download", 1, fetches)
        assertTrue(got.all { it == Obtained.Ready(File(dir, "$hash.image")) })
    }

    /** Both fail at once: one download, one failure counted, one backoff for both. */
    @Test fun `concurrent requests that fail count one failure`() = runBlocking {
        val release = CountDownLatch(1)
        serve = { release.await(10, TimeUnit.SECONDS); null }
        val s = store()
        val asks = (1..3).map { async(Dispatchers.IO) { s.obtain(image) } }
        Thread.sleep(200)
        release.countDown()
        val got = asks.awaitAll()
        assertEquals(1, fetches)
        assertEquals(1, memory.failures("$hash ${image.url}"))
        assertTrue(got.all { it is Obtained.Retry })
    }

    /**
     * Image A is mid-download when the kiosk switches to B, and B is ready. A's download then completes: it must not
     * prune B's file nor make A the last good image (which would put A back up while B can't be had).
     */
    @Test fun `a replaced image's late download never prunes the current image or becomes the last good`() {
        val bBytes = "image b".toByteArray()
        val b = KioskBackgroundImageDto(image.url, sha(bBytes))
        val s = store()
        current = b.sha256
        serve = { ByteArrayInputStream(bBytes) }
        assertTrue(runBlocking { s.obtain(b) } is Obtained.Ready)
        assertEquals(b.sha256, memory.good)
        serve = { ByteArrayInputStream(bytes) }
        runBlocking { s.obtain(image) } // A's download, finishing late
        assertTrue("B kept", File(dir, "${b.sha256}.image").isFile)
        assertEquals("B is still the last good image", b.sha256, memory.good)
        assertEquals(File(dir, "${b.sha256}.image"), runBlocking { s.lastGood() })
    }

    /** N10: a cache hit (every screen-on) writes nothing and prunes nothing once the image is noted ready. */
    @Test fun `repeated cache hits write the prefs once`() {
        val s = store()
        repeat(5) { obtain(s) }
        assertEquals(listOf("succeeded"), memory.writes)
    }

    /** N9: a file that matches its hash but is over the pixel cap can never work: Invalid, not re-downloaded. */
    @Test fun `a hash-matching file over the size caps is invalid, not retried`() {
        size = 5000 to 5000 // 25 MP
        val s = store()
        assertEquals(Obtained.Invalid, obtain(s))
        nowMs += 7L * 60 * 60 * 1000
        assertEquals(Obtained.Invalid, obtain(store()))
        assertEquals("downloaded once", 1, fetches)
        assertTrue(dir.list()!!.none { it.endsWith(".image") })
    }

    /** N8: a server trickling bytes can't hold a download (and its thread) for ever. */
    @Test fun `downloads have an overall time limit`() {
        val client = KioskBackgroundImageStore.downloadClient(OkHttpClient())
        assertEquals(KioskBackgroundImageStore.DOWNLOAD_TIMEOUT_MS, client.callTimeoutMillis.toLong())
        assertFalse("no switch from HTTPS on a redirect", client.followSslRedirects)
    }
}
