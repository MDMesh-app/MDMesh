package com.mdmesh.agent.stress

import android.app.ActivityManager
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.text.method.PasswordTransformationMethod
import com.mdmesh.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/**
 * The exit PIN on the real kiosk HOME (from the kiosk stress run): a typed PIN doesn't outlive the screen going off,
 * the person walking away, or the kiosk it was typed for; the tries-left warning and a half-typed PIN survive what
 * they should (rotation, a keyboard or scanner connecting).
 */
class ExitPinSafetyTest : KioskHarness() {

    private fun field() = edit(pinDialog()!!.window!!.decorView)!!

    private fun configChange(edit: Configuration.() -> Unit) {
        ctl!!.configurationChange(Configuration(activity.resources.configuration).apply(edit))
        settle()
    }

    private fun rotate() = configChange {
        orientation = Configuration.ORIENTATION_LANDSCAPE
        screenWidthDp = 1280
        screenHeightDp = 800
    }

    // --- F3: abandoned input -------------------------------------------------------------------------------------

    /** Typed, not sent, screen off and on: whoever finds the device must not find the PIN one tap from Exit. */
    @Test fun typedPinDoesNotSurviveScreenOffOn() {
        launch(grid)
        tapExitKiosk()
        field().setText(pin)
        coverAndReturn()
        val left = pinDialog()?.let { edit(it.window!!.decorView)!!.text.toString() }.orEmpty()
        assertEquals("typed PIN still in the field after screen off/on", "", left)
    }

    @Test fun showPinIsHiddenAgainAfterScreenOffOn() {
        launch(grid)
        tapExitKiosk()
        field().setText(pin)
        byDescription(pinDialog()!!.window!!.decorView, s(R.string.kiosk_exit_pin_show))!!.performClick()
        coverAndReturn()
        tapExitKiosk()
        assertTrue(field().transformationMethod is PasswordTransformationMethod)
        assertNotNull(byDescription(pinDialog()!!.window!!.decorView, s(R.string.kiosk_exit_pin_show)))
    }

    @Test fun promptClosesAfterAMinuteWithoutInput() {
        launch(grid)
        tapExitKiosk()
        field().setText("48")
        ShadowLooper.idleMainLooper(IDLE_MS - 1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        field().append("2") // still typing: the minute starts again
        ShadowLooper.idleMainLooper(IDLE_MS - 1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("482", field().text.toString())
        ShadowLooper.idleMainLooper(Duration.ofSeconds(2).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        assertNull("prompt still open a minute after the last key", pinDialog())
    }

    // --- One exit per right PIN ----------------------------------------------------------------------------------

    /**
     * A keyboard-wedge scanner whose barcode ends in CR (sent as text) and also an Enter key: two "done" signals for
     * one scan. The first right PIN closes the prompt; the second must not run the exit again.
     */
    @Test fun rightPinFromAScannerWithCrAndEnterExitsOnce() {
        launch(grid)
        tapExitKiosk()
        val dialog = pinDialog()!!
        val f = field()
        f.requestFocus()
        f.onCreateInputConnection(EditorInfo())!!.commitText(pin + "\r", 1)
        f.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        f.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        settle()
        assertEquals("kiosk exits", 1, events("kioskExit"))
        assertEquals("the closed prompt keeps no PIN", "", edit(dialog.window!!.decorView)!!.text.toString())
    }

    /**
     * Done from the soft keyboard, then a hardware Enter for the same PIN (an IME plus a keyboard, or a scanner that
     * sends both): the Enter reaches the field after Done closed the prompt, and must not exit again.
     */
    @Test fun imeDoneThenHardwareEnterExitsOnce() {
        launch(grid)
        tapExitKiosk()
        val f = field()
        f.requestFocus()
        f.setText(pin)
        f.onCreateInputConnection(EditorInfo())!!.performEditorAction(EditorInfo.IME_ACTION_DONE)
        f.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        f.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        settle()
        assertEquals(1, events("kioskExit"))
    }

    // --- F4: the kiosk it was typed for ends ---------------------------------------------------------------------

    @Test fun remoteExitWhileDialogOpenClosesItAndUnlocks() {
        launch(grid)
        tapExitKiosk()
        assertNotNull(pinDialog())
        saveKiosk(null)
        settle()
        assertNull(pinDialog())
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, lockTaskState())
    }

    @Test fun noStalePinDialogAfterRemoteExitAndReentryWhileStopped() {
        launch(grid)
        tapExitKiosk()
        field().setText(pin)
        coverAndReturn {
            saveKiosk(null)
            settle()
            saveKiosk(grid.copy(theme = grid.theme.copy(backgroundColor = "#123456")))
            settle()
        }
        assertNull("unasked Exit kiosk prompt on the re-entered kiosk", pinDialog())
    }

    /** A different kiosk replaced the one the PIN was being typed for, without an idle state in between. */
    @Test fun kioskReplacedWhileTypingClosesThePrompt() {
        launch(grid)
        tapExitKiosk()
        field().setText(pin)
        saveKiosk(grid.copy(password = "9999"))
        settle()
        assertNull(pinDialog())
    }

    /** A new look is the same kiosk: the prompt (and what was typed) stays. */
    @Test fun kioskRecolouredWhileTypingKeepsThePrompt() {
        launch(grid)
        tapExitKiosk()
        field().setText(pin)
        saveKiosk(grid.copy(theme = grid.theme.copy(backgroundColor = "#123456")))
        settle()
        assertEquals(pin, field().text.toString())
    }

    /**
     * PR #192 S5: the right PIN for kiosk A is submitted just as a kiosk.enter of B lands (B is saved, its delivery to
     * the launcher not yet run). The exit was for A: it must not take the device out of B.
     */
    @Test fun pinExitForAKioskReplacedAsItRunsLeavesTheNewKioskLocked() {
        launch(grid)
        tapExitKiosk()
        val d = pinDialog()!!
        edit(d.window!!.decorView)!!.setText(pin)
        val replacement = grid.copy(allowedPackages = listOf(other))
        saveKiosk(replacement) // the store has B; the launcher still shows A (the main looper hasn't run)
        button(d.window!!.decorView, s(R.string.kiosk_exit_confirm))!!.performClick()
        settle()
        assertEquals("the new kiosk is kept", replacement, storedKiosk())
        assertEquals("still locked", ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        assertTrue("the launcher stays", !activity.isFinishing)
        assertEquals("no exit recorded", 0, events("kioskExit"))
    }

    private fun lockedMessage(): String =
        textsOf(lockedDialog()!!.window!!.decorView).first { it.startsWith("Too many") }

    private fun textsOf(v: android.view.View): List<String> = when (v) {
        is android.widget.TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { textsOf(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun idle(seconds: Long) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
        settle()
    }

    /** PR #192 N4: "Try again in N" counts down while it is shown, and the dialog closes when the lockout ends. */
    @Test fun exitLockedCountsDownAndClosesWhenTheLockoutEnds() {
        launch(grid)
        tapExitKiosk()
        repeat(3) { submitPin("0000") }
        assertTrue(lockedMessage(), lockedMessage().endsWith("30 s."))
        idle(10)
        assertTrue(lockedMessage(), lockedMessage().endsWith("20 s."))
        idle(21)
        assertNull("closed once the exit can be tried again", lockedDialog())
    }

    /** PR #192 N4: a long lockout's dialog doesn't stay over the kiosk for good: it closes as the PIN prompt does. */
    @Test fun exitLockedClosesAfterAMinuteOfALongLockout() {
        launch(grid)
        tapExitKiosk()
        repeat(3) { submitPin("0000") }
        lockedDialog()!!.dismiss(); settle()
        idle(31)
        tapExitKiosk()
        submitPin("0000") // the 4th: locked for a minute
        lockedDialog()!!.dismiss(); settle()
        idle(61)
        tapExitKiosk()
        submitPin("0000") // the 5th: locked for 5 minutes
        assertTrue(lockedMessage(), lockedMessage().endsWith("5 min."))
        idle(IDLE_MS / 1000 + 1)
        assertNull(lockedDialog())
    }

    // --- F9 / F10: what a rebuild keeps --------------------------------------------------------------------------

    @Test fun triesLeftWarningSurvivesRotation() {
        launch(grid)
        tapExitKiosk()
        submitPin("0000")
        submitPin("0000")
        val warn = app.resources.getQuantityString(R.plurals.kiosk_exit_wrong_pin, 1, 1)
        assertNotNull(findText(pinDialog()!!.window!!.decorView, warn))
        rotate()
        assertNotNull("'1 try left' warning gone after rotation", findText(pinDialog()!!.window!!.decorView, warn))
    }

    @Test fun triesLeftWarningShownWhenThePromptIsOpenedAgain() {
        launch(grid)
        tapExitKiosk()
        submitPin("0000")
        pinDialog()!!.cancel()
        settle()
        tapExitKiosk()
        val warn = app.resources.getQuantityString(R.plurals.kiosk_exit_wrong_pin, 2, 2)
        assertNotNull(findText(pinDialog()!!.window!!.decorView, warn))
    }

    /** A Bluetooth or USB scanner or keyboard connecting mid-entry. */
    @Test fun keyboardConnectKeepsTypedPin() {
        launch(grid)
        tapExitKiosk()
        field().setText("48")
        configChange {
            keyboard = Configuration.KEYBOARD_QWERTY
            hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO
            keyboardHidden = Configuration.KEYBOARDHIDDEN_NO
        }
        assertNotNull(pinDialog())
        assertEquals("typed digits lost when a keyboard or scanner connected", "48", field().text.toString())
    }

    @Test fun rebuildsKeepOneDialogAndNoPinInSavedState() {
        launch(grid)
        tapExitKiosk()
        field().setText(pin)
        val out = android.os.Bundle()
        ctl!!.saveInstanceState(out)
        assertTrue("typed PIN written to saved state", !deepContains(out, pin))
        recreate()
        recreate()
        assertEquals(1, ShadowDialog.getShownDialogs().count { it.isShowing })
    }

    private companion object {
        /** The prompt's idle timeout (ExitPinDialog.IDLE_TIMEOUT_MS), spelled out so the test pins the value. */
        const val IDLE_MS = 60_000L
    }

    private fun deepContains(b: android.os.Bundle, needle: String): Boolean = b.keySet().any { k ->
        @Suppress("DEPRECATION")
        when (val v = b.get(k)) {
            is android.os.Bundle -> deepContains(v, needle)
            is android.util.SparseArray<*> -> (0 until v.size()).any { i -> v.valueAt(i).toString().contains(needle) }
            else -> v?.toString()?.contains(needle) == true
        }
    }
}
