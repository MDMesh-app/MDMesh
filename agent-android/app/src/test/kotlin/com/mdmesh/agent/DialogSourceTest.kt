package com.mdmesh.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dialogs use androidx.appcompat's AlertDialog, which takes the app theme's v2 dialog style. The framework
 * android.app.AlertDialog takes the device's own style instead and looks different on every OEM.
 */
class DialogSourceTest {

    private fun hits(pattern: Regex, except: Set<String> = emptySet()): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return File(root, "app/src/main").walkTopDown().filter { it.extension == "kt" && it.name !in except }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    "${f.name}:${i + 1}".takeIf { pattern.containsMatchIn(line) }
                }
            }
            .toList()
    }

    @Test fun `no framework AlertDialog in the app`() {
        val framework = hits(Regex("""\bandroid\.app\.AlertDialog\b"""))
        assertEquals("framework AlertDialog used at", emptyList<String>(), framework)
    }

    /** One dialog layout for all: a dialog built on AlertDialog.Builder gets AppCompat's misaligned template. */
    @Test fun `every dialog is an MdmDialog`() {
        val builder = Regex("""\bAlertDialog\.Builder\b|\.setPositiveButton\(|\.setMessage\(""")
        val direct = hits(builder, except = setOf("MdmDialog.kt"))
        assertEquals("dialogs built outside MdmDialog", emptyList<String>(), direct)
    }

    /** Dialogs an activity opens are closed with it and reopened after a rotation (DialogSlot). */
    @Test fun `activities that open dialogs keep them in a DialogSlot`() {
        val creates = Regex("""\b(MdmDialog|ExitPinDialog)\.create\(""")
        val opens = hits(creates, except = setOf("MdmDialog.kt", "ExitPinDialog.kt"))
            .map { it.substringBefore(':') }.toSet()
        assertTrue("no activity opens a dialog; update this guard", opens.isNotEmpty())
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        for (name in opens) {
            val src = File(root, "app/src/main").walkTopDown().single { it.name == name }.readText()
            for (call in listOf("dialogs.save(", "dialogs.restore(", "dialogs.dismiss()", "dialogs.takeRestored()")) {
                assertTrue("$name doesn't call $call", call in src)
            }
        }
    }
}
