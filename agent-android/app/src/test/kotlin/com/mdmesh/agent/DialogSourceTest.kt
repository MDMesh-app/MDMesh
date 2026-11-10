package com.mdmesh.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Dialogs use androidx.appcompat's AlertDialog, which takes the app theme's v2 dialog style. The framework
 * android.app.AlertDialog takes the device's own style instead and looks different on every OEM.
 */
class DialogSourceTest {

    @Test fun `no framework AlertDialog in the app`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val framework = Regex("""\bandroid\.app\.AlertDialog\b""")
        val hits = File(root, "app/src/main").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    "${f.name}:${i + 1}".takeIf { framework.containsMatchIn(line) }
                }
            }
            .toList()
        assertEquals("framework AlertDialog used at", emptyList<String>(), hits)
    }
}
