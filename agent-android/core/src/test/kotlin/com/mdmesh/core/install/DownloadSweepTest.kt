package com.mdmesh.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadSweepTest {

    @get:Rule val dir = TemporaryFolder()

    private val now = 10 * 60 * 60_000L

    private fun file(name: String, ageMs: Long) =
        dir.newFile(name).apply { setLastModified(now - ageMs) }

    private val me = 123

    @Test
    fun `this process's downloads go only when old and no install is using them`() {
        val orphan = file("mdm-install-123-1.apk", ageMs = 2 * 60 * 60_000L)
        val inUse = file("mdm-install-123-2.apk", ageMs = 2 * 60 * 60_000L) // a commit waiting on the platform
        val fresh = file("mdm-install-123-3.apk", ageMs = 60_000L)
        val other = file("other.apk", ageMs = 2 * 60 * 60_000L)

        val deleted = DownloadSweep.sweep(dir.root, inUse = setOf(inUse), now = now, owner = me)

        assertEquals(listOf(orphan), deleted)
        assertTrue(inUse.exists() && fresh.exists() && other.exists())
        assertTrue(!orphan.exists())
    }

    @Test
    fun `a dead process's download goes at once, however fresh`() {
        // Android killed the agent mid-download and restarted it a minute later: no later sweep may be needed.
        val leftover = file("mdm-install-999-7.apk", ageMs = 60_000L)
        val olderAgent = file("mdm-install-8.apk", ageMs = 60_000L) // named before downloads carried their process

        val deleted = DownloadSweep.sweep(dir.root, inUse = emptySet(), now = now, owner = me)

        assertEquals(setOf(leftover, olderAgent), deleted.toSet())
    }

    @Test
    fun `a missing cache directory is not an error`() {
        assertTrue(DownloadSweep.sweep(dir.root.resolve("gone"), emptySet(), now, me).isEmpty())
    }
}

class DownloadCacheTest {

    @get:Rule val dir = TemporaryFolder()

    private var now = 10 * 60 * 60_000L
    private val cache = DownloadCache({ dir.root }, owner = 123, now = { now })
    private val old = now - 2 * 60 * 60_000L

    @Test
    fun `a download is protected from the sweep from before its first byte`() {
        val file = cache.newFile()
        file.writeText("apk")
        file.setLastModified(old) // a stalled download: no write for two hours
        cache.sweep()
        assertTrue(file.exists())
    }

    @Test
    fun `starting a download sweeps what a dead process left`() {
        val orphan = dir.newFile("mdm-install-999-1.apk") // a dead process's, a minute old
        cache.newFile()
        assertTrue(!orphan.exists())
    }

    @Test
    fun `a released download is deleted and no longer protected`() {
        val file = cache.newFile()
        file.writeText("apk")
        cache.release(file)
        assertTrue(!file.exists())

        file.writeText("again")
        file.setLastModified(old)
        cache.sweep()
        assertTrue(!file.exists())
    }

    @Test
    fun `the periodic sweep removes this process's own old leftover without an install or a restart`() {
        // A reused process id, or an install that never released its file: only the age rule lets it go.
        val leftover = dir.newFile("mdm-install-123-9.apk").apply { setLastModified(now) }
        assertTrue(cache.sweepIfDue().isEmpty())
        assertTrue("fresh: kept", leftover.exists())

        now += DownloadSweep.MIN_AGE_MS
        assertEquals(listOf(leftover), cache.sweepIfDue())
        assertTrue(!leftover.exists())
    }

    @Test
    fun `the periodic sweep lists the cache at most once per period`() {
        assertTrue(cache.sweepIfDue().isEmpty()) // the first one is due
        val orphan = dir.newFile("mdm-install-999-1.apk") // a dead process's: any sweep would take it

        now += DownloadSweep.PERIOD_MS - 1
        assertTrue(cache.sweepIfDue().isEmpty())
        assertTrue("not due yet: the cache was not listed", orphan.exists())

        now += 1
        assertEquals(listOf(orphan), cache.sweepIfDue())
    }

    @Test
    fun `any sweep restarts the period, and a clock set back does not stop the periodic one`() {
        cache.newFile() // starting a download sweeps
        val orphan = dir.newFile("mdm-install-999-1.apk")
        now += DownloadSweep.PERIOD_MS / 2
        assertTrue(cache.sweepIfDue().isEmpty())
        assertTrue(orphan.exists())

        now -= 24 * 60 * 60_000L // the wall clock moved back a day
        assertEquals(listOf(orphan), cache.sweepIfDue())
    }

    @Test
    fun `a file in use is never removed by the periodic sweep, however old`() {
        val file = cache.newFile()
        file.writeText("apk")
        repeat(10) {
            now += DownloadSweep.MIN_AGE_MS
            file.setLastModified(now - DownloadSweep.MIN_AGE_MS * 2)
            cache.sweepIfDue()
        }
        assertTrue(file.exists())
    }
}
