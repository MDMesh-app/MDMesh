package com.mdmesh.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard: the kiosk launcher ends kiosk only from `doExit`, and only the PIN check calls `doExit`. Anything
 * else that leaves kiosk on the device (a crash-loop bail-out, a bounce counter) is a way out for a user who can
 * close the pinned app on purpose. The server's kiosk.exit and config.apply run in the check-in service, not here.
 * It lives in :app, next to the launcher, so any change to the launcher re-runs it.
 */
class KioskLauncherExitTest {

    /** The launcher's source with comments and string literals blanked (same length, same line breaks). */
    private fun launcherSource(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val file = File(root, "app/src/main/kotlin/com/mdmesh/agent/KioskLauncherActivity.kt")
        assertTrue("missing $file", file.isFile)
        val blank = { m: MatchResult -> m.value.replace(Regex("[^\n]"), " ") }
        return file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), blank)
            .replace(Regex("//[^\n]*"), blank)
            .replace(Regex("\"(?:\\\\.|[^\"\\\\\n])*\""), blank)
    }

    /** The text of `fun name(...) { ... }`, by brace matching. */
    private fun body(src: String, name: String): IntRange {
        val start = Regex("\\bfun\\s+$name\\s*\\(").find(src)?.range?.first ?: error("no fun $name")
        var i = src.indexOf('{', start)
        var depth = 0
        while (true) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return start..i
            }
            i++
        }
    }

    private fun lineOf(src: String, at: Int) = src.substring(0, at).count { it == '\n' } + 1

    @Test
    fun `only the admin-PIN exit ends kiosk`() {
        val src = launcherSource()
        val doExit = body(src, "doExit")
        val exits = Regex("\\.exit\\s*\\(").findAll(src).map { it.range.first }.toList()
        assertTrue("doExit no longer calls exit; update this guard", exits.any { it in doExit })
        val outside = exits.filter { it !in doExit }.map { lineOf(src, it) }
        assertEquals("KioskLauncherActivity.kt lines ending kiosk outside doExit", emptyList<Int>(), outside)
        // Clearing the saved kiosk is an exit too.
        val clears = Regex("\\.save\\s*\\(\\s*null\\s*\\)").findAll(src).map { lineOf(src, it.range.first) }.toList()
        assertEquals("KioskLauncherActivity.kt lines clearing the kiosk state", emptyList<Int>(), clears)
    }

    @Test
    fun `only the PIN check calls doExit, and it goes through ExitPinGate`() {
        val src = launcherSource()
        val check = body(src, "onPinEntered")
        val calls = Regex("(?<!fun )\\bdoExit\\s*\\(\\s*\\)").findAll(src).map { it.range.first }.toList()
        assertTrue("onPinEntered no longer calls doExit; update this guard", calls.any { it in check })
        val outside = calls.filter { it !in check }.map { lineOf(src, it) }
        assertEquals("KioskLauncherActivity.kt lines calling doExit outside onPinEntered", emptyList<Int>(), outside)
        val checksWithGate = Regex("\\bpinGate\\.attempt\\s*\\(").containsMatchIn(src.substring(check))
        assertTrue("onPinEntered must check the PIN with ExitPinGate", checksWithGate)
        // And only on the right-PIN branch.
        val onOpen = Regex("Outcome\\.Open\\s*->\\s*doExit\\s*\\(\\s*\\)").findAll(src.substring(check)).count()
        assertEquals("doExit must be the Outcome.Open branch, and only that", 1, onOpen)
        assertEquals(1, calls.count { it in check })
    }

    @Test
    fun `the launcher never compares PINs itself`() {
        // An `==` on the entered text or the password skips the lockout (and isn't constant-time).
        val compare = Regex("toString\\(\\)\\s*[!=]=|[!=]=\\s*\\w*\\.?password\\b|\\bpassword\\s*[!=]=")
        val lines = compare.findAll(launcherSource()).map { it.range.first }.toList()
        assertEquals("KioskLauncherActivity.kt lines comparing a PIN", emptyList<Int>(), lines)
    }

    @Test
    fun `the launcher never stops lock task on a crash loop`() {
        val src = launcherSource()
        // stopLockTask belongs to the exit and the idle (no kiosk) state only.
        val allowed = listOf(body(src, "doExit"), body(src, "stopLockTaskSafely"))
        val stops = Regex("\\bstopLockTask\\s*\\(\\s*\\)").findAll(src).map { it.range.first }
            .filter { at -> allowed.none { at in it } }.map { lineOf(src, it) }.toList()
        val callers = Regex("\\bstopLockTaskSafely\\s*\\(\\s*\\)").findAll(src).count()
        assertEquals("stopLockTask outside doExit/stopLockTaskSafely", emptyList<Int>(), stops)
        // One definition + the no-kiosk branch of applyState.
        assertEquals("stopLockTaskSafely callers changed; check none is a crash path", 2, callers)
    }
}
