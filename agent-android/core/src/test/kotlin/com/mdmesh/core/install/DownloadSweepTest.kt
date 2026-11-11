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

    @Test
    fun `deletes only old downloads no install is using`() {
        val orphan = file("mdm-install-1.apk", ageMs = 2 * 60 * 60_000L)
        val inUse = file("mdm-install-2.apk", ageMs = 2 * 60 * 60_000L) // a commit waiting on the platform
        val fresh = file("mdm-install-3.apk", ageMs = 60_000L)
        val other = file("other.apk", ageMs = 2 * 60 * 60_000L)

        val deleted = DownloadSweep.sweep(dir.root, inUse = setOf(inUse), now = now)

        assertEquals(listOf(orphan), deleted)
        assertTrue(inUse.exists() && fresh.exists() && other.exists())
        assertTrue(!orphan.exists())
    }

    @Test
    fun `a missing cache directory is not an error`() {
        assertTrue(DownloadSweep.sweep(dir.root.resolve("gone"), emptySet(), now).isEmpty())
    }
}

class DownloadCacheTest {

    @get:Rule val dir = TemporaryFolder()

    private val now = 10 * 60 * 60_000L
    private val cache = DownloadCache({ dir.root }, { now })
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
        val orphan = dir.newFile("mdm-install-1.apk").apply { setLastModified(old) }
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
}
