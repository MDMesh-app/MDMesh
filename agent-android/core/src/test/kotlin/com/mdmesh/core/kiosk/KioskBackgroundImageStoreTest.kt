package com.mdmesh.core.kiosk

import com.mdmesh.core.kiosk.KioskBackgroundImageStore.Obtained
import com.mdmesh.proto.KioskBackgroundImageDto
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
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
    private val memory = MemFailures()
    private lateinit var dir: File

    private class MemFailures : KioskBackgroundImageStore.FailureMemory {
        var entry: Pair<String, Int>? = null
        val invalid = mutableSetOf<String>()
        var good: String? = null
        override fun failures(hash: String) = entry?.takeIf { it.first == hash }?.second ?: 0
        override fun failed(hash: String, count: Int) { entry = hash to count }
        override fun succeeded(hash: String) { entry = null; good = hash }
        override fun isInvalid(hash: String) = hash in invalid
        override fun markInvalid(hash: String) { invalid += hash }
        override fun lastGood() = good
    }

    private fun store(
        dirFile: File = File(temp.root, "kiosk-background").also { dir = it },
    ) = KioskBackgroundImageStore(
        dir = { dirFile },
        fetch = { fetches++; serve() },
        bounds = { f -> if (f.length() > 0) 100 to 100 else null },
        failures = memory,
        clocks = KioskBackgroundImageStore.Clocks(elapsed = { nowMs }, wallNow = { wallMs }, jitter = { 0.0 }),
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
        assertEquals(2, memory.failures(hash))
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
}
