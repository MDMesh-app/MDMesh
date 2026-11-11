package com.mdmesh.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Source guards for the status screen and the kiosk launcher. */
class ScreenSourceTest {

    private val main: File by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        File(root, "app/src/main/kotlin/com/mdmesh/agent")
    }

    private fun hits(files: Sequence<File>, pattern: Regex): List<String> = files.flatMap { f ->
        f.readLines().mapIndexedNotNull { i, line -> "${f.name}:${i + 1}".takeIf { pattern.containsMatchIn(line) } }
    }.toList()

    @Test fun `no toasts in the app`() {
        // Toasts vanish before everyone can read them; results show inline (callouts, the PIN dialog's error line).
        val all = main.walkTopDown().filter { it.extension == "kt" }
        assertEquals(emptyList<String>(), hits(all, Regex("""\bToast\b""")))
    }

    @Test fun `the kiosk launcher draws only in the kiosk's colours`() {
        // No theme colours: with configChanges the launcher isn't recreated, so a theme colour would stay in the old
        // light/dark mode; and the admin's colours must not mix with ours.
        val launcher = listOf("KioskLauncherActivity.kt", "ui/KioskRenderer.kt", "ui/KioskViews.kt")
            .map { File(main, it) }.asSequence()
        assertEquals(emptyList<String>(), hits(launcher, Regex("""R\.color\.|getColor\(|\?attr/|colorPrimary""")))
    }

    @Test fun `the launcher sets its content view once`() {
        // Screens swap inside KioskRenderer's root: a setContentView per state drew a blank frame in between.
        val launcher = sequenceOf(File(main, "KioskLauncherActivity.kt"), File(main, "ui/KioskRenderer.kt"))
        assertEquals(1, hits(launcher, Regex("""\bsetContentView\(""")).size)
    }
}
