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

    private fun backgroundStoreSource(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val file = File(root, "core/src/main/kotlin/com/mdmesh/core/kiosk/KioskBackgroundImageStore.kt")
        assertTrue("missing $file", file.isFile)
        return file.readText()
    }

    private fun checkInWorkerSource(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val file = File(root, "core/src/main/kotlin/com/mdmesh/core/sync/CheckInWorker.kt")
        assertTrue("missing $file", file.isFile)
        return file.readText()
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

    @Test
    fun `an unchanged kiosk state still re-pins and runs the return policy`() {
        val src = launcherSource()
        val apply = src.substring(body(src, "applyState"))
        // The same state comes again each time the launcher is back in the foreground; returning early for it
        // would skip the re-pin and the crash-loop count.
        assertTrue(
            "applyState must not return early for an unchanged state",
            !Regex("p\\s*==\\s*previous\\s*\\)\\s*return").containsMatchIn(apply),
        )
        val returns = Regex("\\breturn\\b").findAll(apply).map { it.range.first }.toList()
        val pin = apply.indexOf("startLockTaskSafely()")
        val policy = apply.indexOf("returns.onApplied(")
        assertTrue("applyState must pin and run the return policy", pin in 0 until policy)
        val idle = apply.indexOf("setContentView(idleView())")
        assertTrue(
            "applyState's only early return is the no-kiosk branch",
            returns.all { it in idle until pin },
        )
    }

    @Test
    fun `an unchanged state keeps a loaded or loading background image`() {
        val src = launcherSource()
        val apply = src.substring(body(src, "applyState"))
        assertTrue(
            "applyState must drop the image only when the state wants another one",
            Regex("if\\s*\\(\\s*p\\.theme\\.backgroundImage\\s*!=\\s*backgroundFor\\s*\\)\\s*clearBackground\\(\\)")
                .containsMatchIn(apply),
        )
        assertTrue("applyState must (re)start a missing image", "loadBackground(p)" in apply)
        val load = src.substring(body(src, "loadBackground"))
        assertTrue(
            "loadBackground must not start a second download, or reload a loaded image",
            "if (backgroundBitmap != null || backgroundFetch?.isActive == true) return" in load,
        )
    }

    @Test
    fun `background completion only renders the current state and preserves a crash hold`() {
        val src = launcherSource()
        val fetch = src.substring(body(src, "loadBackground"))
        assertTrue(
            "background completion must reject a state that no longer wants this image",
            "if (current.theme.backgroundImage != requested) return@launch" in fetch,
        )
        assertTrue(
            "background completion must redraw the hold screen instead of relaunching",
            "if (holding) hold(current)" in fetch,
        )
        assertTrue("background completion must not call show while held", "|| holding" !in fetch)
    }

    @Test
    fun `the background image is decoded once, off the main thread`() {
        val src = launcherSource()
        val frame = src.substring(body(src, "frame"))
        assertTrue("frame must reuse the decoded bitmap", "decodeBackground" !in frame && "backgroundBitmap" in frame)
        val obtain = src.substring(body(src, "obtainBackgroundImage"))
        assertTrue(
            "the decode must run on a background dispatcher",
            Regex("withContext\\(Dispatchers\\.(IO|Default)\\)\\s*\\{\\s*decodeBackground").containsMatchIn(obtain),
        )
    }

    @Test
    fun `background download retries once after a transient failure`() {
        val src = launcherSource()
        val obtain = src.substring(body(src, "obtainBackgroundImage"))
        assertTrue("background download must make an initial attempt", "backgroundImages.obtain(requested)" in obtain)
        assertTrue("background download must defer a bounded retry", "delay(BACKGROUND_RETRY_MS)" in obtain)
    }

    @Test
    fun `overlapping image downloads use distinct temporary files`() {
        val src = backgroundStoreSource()
        assertTrue("background staging must use a unique temporary file", "File.createTempFile" in src)
        assertTrue(
            "background staging must not use one hash-named shared download",
            ".\${request.hash}.download" !in src,
        )
    }

    @Test
    fun `on-device exit requests an immediate check-in after releasing kiosk policy`() {
        val src = launcherSource()
        val exit = body(src, "doExit")
        val text = src.substring(exit)
        val release = text.indexOf("applier.exit(")
        val checkIn = text.indexOf("CheckInWorker.scheduleAfterCurrent(applicationContext)")
        assertTrue("doExit must release kiosk policy", release >= 0)
        assertTrue("doExit must request an immediate check-in", checkIn >= 0)
        assertTrue("doExit must request check-in after releasing kiosk policy", checkIn > release)
    }

    @Test
    fun `exit check-in follows rather than discards an in-flight check-in`() {
        val src = checkInWorkerSource()
        val queued = body(src, "scheduleAfterCurrent")
        val text = src.substring(queued)
        assertTrue("follow-up check-in must append after active work", "ExistingWorkPolicy.APPEND_OR_REPLACE" in text)
        assertTrue("follow-up check-in must share the immediate check-in chain", "\"\$UNIQUE_NAME-now\"" in text)
    }
}
