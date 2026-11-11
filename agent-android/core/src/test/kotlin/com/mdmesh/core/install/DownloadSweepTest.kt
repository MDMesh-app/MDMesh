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
