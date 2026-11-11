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
    private var fetches = 0
    private var serve: () -> InputStream? = { ByteArrayInputStream(bytes) }
    private val memory = MemFailures()
    private lateinit var dir: File

    private class MemFailures : KioskBackgroundImageStore.FailureMemory {
        var entry: Triple<String, Int, Long>? = null
        override fun get(hash: String) = entry?.takeIf { it.first == hash }?.let { it.second to it.third }
        override fun record(hash: String, count: Int, nextAttemptMs: Long) {
            entry = Triple(hash, count, nextAttemptMs)
        }
        override fun clear() { entry = null }
    }

    private fun store(
        dirFile: File = File(temp.root, "kiosk-background").also { dir = it },
    ) = KioskBackgroundImageStore(
        dir = { dirFile },
        fetch = { fetches++; serve() },
        bounds = { f -> if (f.length() > 0) 100 to 100 else null },
        failures = memory,
        now = { nowMs },
        jitter = { 0.0 },
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
            val at = (obtain(s) as Obtained.Retry).atMs
            val wait = at - nowMs
            nowMs = at
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
}
