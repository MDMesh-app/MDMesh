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
        val builder = Regex(
            """\bAlertDialog\.Builder\b|\.setPositiveButton\(|\.setMessage\(|\b(AppCompat)?Dialog\(|""" +
                """\bMaterialAlertDialogBuilder\b|\bDialogFragment\b""",
        )
        val direct = hits(builder, except = setOf("MdmDialog.kt"))
        assertEquals("dialogs built outside MdmDialog", emptyList<String>(), direct)
    }

    /** Source with comments and string literals blanked, so a guard can't be met by a comment. */
    private fun code(file: File): String {
        val blank = { m: MatchResult -> m.value.replace(Regex("[^\n]"), " ") }
        return file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), blank)
            .replace(Regex("//[^\n]*"), blank)
            .replace(Regex("\"(?:\\\\.|[^\"\\\\\n])*\""), blank)
    }

    /**
     * Dialogs an activity opens are closed with it and reopened after a recreation (DialogSlot); an activity that
     * handles config changes itself rebuilds them too. Showing one at all takes a DialogSlot (the compiler checks it).
     */
    @Test fun `activities that open dialogs keep them in a DialogSlot`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val sources = File(root, "app/src/main").walkTopDown().filter { it.extension == "kt" }.toList()
        val creates = Regex("""\b(MdmDialog|ExitPinDialog)\.create\(""")
        val opens = sources.filter { it.name !in setOf("MdmDialog.kt", "ExitPinDialog.kt") }
            .filter { creates.containsMatchIn(code(it)) }
        assertTrue("no activity opens a dialog; update this guard", opens.isNotEmpty())
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        for (file in opens) {
            val src = code(file)
            val calls = listOf("dialogs.save(", "dialogs.restore(", "dialogs.dismiss()", "dialogs.takeRestored()")
            for (call in calls) assertTrue("${file.name} doesn't call $call", call in src)
            val activity = file.nameWithoutExtension
            val handlesConfig = Regex("""android:name="\.$activity"[^>]*android:configChanges""").containsMatchIn(
                manifest.replace(Regex("\\s+"), " "),
            )
            if (handlesConfig) {
                val forwards = Regex("""fun onConfigurationChanged\([^)]*\)\s*\{[^}]*dialogs\.onConfigurationChanged""")
                assertTrue("${file.name} must rebuild its dialog on a config change", forwards.containsMatchIn(src))
            }
        }
    }
}
