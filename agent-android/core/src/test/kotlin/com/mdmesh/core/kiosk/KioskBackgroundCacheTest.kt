package com.mdmesh.core.kiosk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The kiosk image cache keeps only the current image, and drops downloads a killed process left behind. */
class KioskBackgroundCacheTest {

    @get:Rule val temp = TemporaryFolder()

    private fun file(dir: File, name: String, ageMs: Long, now: Long): File =
        File(dir, name).apply {
            writeText(name)
            setLastModified(now - ageMs)
        }

    @Test
    fun `only the current image and recent downloads stay`() {
        val dir = temp.newFolder("kiosk-background")
        val now = 10L * STALE_DOWNLOAD_MS
        val current = file(dir, "${"a".repeat(64)}.image", 0, now)
        file(dir, "${"b".repeat(64)}.image", 0, now)
        file(dir, "${"c".repeat(64)}.image", 5 * STALE_DOWNLOAD_MS, now)
        file(dir, ".${"d".repeat(64)}.123.download", STALE_DOWNLOAD_MS + 1, now)
        val fresh = file(dir, ".${"e".repeat(64)}.456.download", 1_000, now)
        val other = file(dir, "notes.txt", 5 * STALE_DOWNLOAD_MS, now)

        pruneBackgroundCache(dir, keep = current, nowMs = now)

        assertEquals(
            setOf(current.name, fresh.name, other.name),
            dir.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun `a missing directory is not an error`() {
        val dir = File(temp.root, "absent")
        pruneBackgroundCache(dir, keep = File(dir, "x.image"), nowMs = 0)
    }
}
