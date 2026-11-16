package com.mdmesh.agent

import com.mdmesh.agent.KioskExitDialogs.LOCKED
import com.mdmesh.agent.KioskExitDialogs.PIN
import com.mdmesh.agent.KioskExitDialogs.UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An exit dialog open before the launcher was recreated comes back only to a screen that has the exit on it, and
 * only once. Otherwise a PIN prompt nobody asked for can pop up later (kiosk off, rotate, kiosk back on).
 */
class KioskExitDialogRestoreTest {

    @Test fun `comes back on the grid and on the hold screen`() {
        for (tag in listOf(PIN, LOCKED, UNAVAILABLE)) {
            assertEquals(tag, KioskExitDialogs.reopenOn(LauncherScreen.GRID, tag))
            assertEquals(tag, KioskExitDialogs.reopenOn(LauncherScreen.HOLD, tag))
        }
    }

    @Test fun `is dropped when kiosk is off or the pinned app is in front`() {
        for (tag in listOf(PIN, LOCKED, UNAVAILABLE)) {
            assertNull(KioskExitDialogs.reopenOn(LauncherScreen.IDLE, tag))
            assertNull(KioskExitDialogs.reopenOn(LauncherScreen.PINNED_APP, tag))
        }
        LauncherScreen.entries.forEach { assertNull(KioskExitDialogs.reopenOn(it, null)) }
    }

    @Test fun `an unknown tag never opens anything`() {
        LauncherScreen.entries.forEach { assertNull(KioskExitDialogs.reopenOn(it, "something-else")) }
    }

    /** Taken once, first thing, on every state the launcher applies: so it can't outlive the screen it was for. */
    @Test fun `applyState takes the restored dialog before anything else`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val src = File(root, "app/src/main/kotlin/com/mdmesh/agent/KioskLauncherActivity.kt").readText()
        val start = Regex("""fun applyState\([^)]*\)\s*\{""").find(src) ?: error("no applyState")
        val firstStatement = src.substring(start.range.last + 1).lineSequence().map { it.trim() }
            .first { it.isNotEmpty() && !it.startsWith("//") }
        assertTrue(firstStatement, "dialogs.takeRestored()" in firstStatement)
        assertEquals("takeRestored() is called once", 1, Regex("""\.takeRestored\(\)""").findAll(src).count())
    }
}
