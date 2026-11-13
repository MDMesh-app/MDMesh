package com.mdmesh.agent.stress

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import com.mdmesh.agent.KioskLauncherActivity
import com.mdmesh.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric

/**
 * Lifecycle chaos on the kiosk HOME: returns, recreation, process death, remote changes. Ported from the adversarial
 * stress run (brain review "kiosk stress results", F1-F8).
 */
class KioskLauncherStressTest : KioskHarness() {

    // --- Single-app returns ------------------------------------------------------------------------------------

    @Test fun enterSingleLaunchesPinnedOnceAndLocks() {
        launch(single)
        assertEquals(1, pinnedStarts())
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
    }

    /** Each return to HOME must open the pinned app once (two opens per return = a crash counted twice upstream). */
    @Test fun returnToHomeOpensPinnedAppOnce() {
        launch(single)
        drainStarts()
        coverAndReturn()
        assertEquals("pinned app starts for one return to HOME", 1, pinnedStarts())
    }

    @Test fun fourReturnsInAMinuteHold() {
        launch(single)
        bounce(4)
        assertTrue("hold screen after a crash loop", holdShowing())
        drainStarts()
        coverAndReturn() // screen off/on on the hold screen
        assertTrue(holdShowing())
        assertEquals("held: the app is not reopened on screen-on", 0, pinnedStarts())
    }

    /**
     * The hold must continue through a recreation (rotation on polish; locale change / bold text / restore on p4p5).
     * The crash guard's window is 60 s of wall clock; a recreation more than a minute into the hold loses the
     * activity's `held` flag and reopens the crashing app.
     */
    @Test fun holdSurvivesRecreationAfterTheGuardWindow() {
        launch(single)
        bounce(4)
        assertTrue(holdShowing())
        ageCrashFaults(61_000)
        drainStarts()
        recreate()
        assertTrue("still holding after recreation", holdShowing())
        assertEquals("crashing app not reopened by a recreation", 0, pinnedStarts())
    }

    @Test fun holdSurvivesProcessDeathRestoreAfterTheGuardWindow() {
        launch(single)
        bounce(4)
        ageCrashFaults(61_000)
        drainStarts()
        processDeathRestore()
        assertTrue("still holding after restore", holdShowing())
        assertEquals(0, pinnedStarts())
    }

    @Test fun holdOpenAgainDoubleTapOpensOnce() {
        launch(single)
        bounce(4)
        drainStarts()
        val again = button(decor, s(R.string.kiosk_hold_retry))!!
        again.performClick()
        // A real second tap only reaches the button if it is still on screen.
        if (again.isAttachedToWindow && again.isShown) again.performClick()
        settle()
        assertEquals("one tap's worth of app starts", 1, pinnedStarts())
    }

    /** Pinned app uninstalled: HOME falls back to the grid; returns to it must not turn into "App closed". */
    @Test fun pinnedAppGoneDoesNotClaimItCrashed() {
        launch(single)
        uninstall(pinned)
        bounce(5)
        assertFalse("hold 'App closed' shown for an app that isn't installed", holdShowing())
    }

    @Test fun pinnedAppDisabledFallsBackWithoutCrash() {
        launch(single)
        uninstall(pinned)
        coverAndReturn()
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        assertNotNull(findText(decor, s(R.string.kiosk_header)))
    }

    /** F8: the grid says plainly that the kiosk app isn't installed. */
    @Test fun pinnedAppGoneSaysItIsNotInstalled() {
        launch(single)
        uninstall(pinned)
        coverAndReturn()
        assertNotNull("'not installed' notice", findText(decor, app.getString(R.string.kiosk_app_missing, pinned)))
    }

    /** F8: reinstalled, the kiosk app opens again at the next return, without counting the missing time. */
    @Test fun pinnedAppReinstalledResumesTheKiosk() {
        launch(single)
        uninstall(pinned)
        bounce(5)
        install(pinned)
        drainStarts()
        coverAndReturn()
        assertEquals("the reinstalled app opens", 1, pinnedStarts())
        assertFalse(holdShowing())
    }

    // --- Remote changes while the exit dialog is open ------------------------------------------------------------

    @Test fun remoteExitWhileDialogOpenClosesItAndUnlocks() {
        launch(grid)
        tapExitKiosk()
        assertNotNull(pinDialog())
        saveKiosk(null)
        settle()
        assertNull(pinDialog())
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, lockTaskState())
    }

    /**
     * Remote kiosk.exit while HOME is stopped (screen off) with the PIN dialog up, then a kiosk entered again later:
     * the old prompt must not pop up by itself on the new kiosk.
     */
    @Test fun noStalePinDialogAfterRemoteExitAndReentryWhileStopped() {
        launch(grid)
        tapExitKiosk()
        edit(pinDialog()!!.window!!.decorView)!!.setText(pin) // typed, not submitted
        coverAndReturn {
            saveKiosk(null)
            settle()
            saveKiosk(grid.copy(theme = grid.theme.copy(backgroundColor = "#123456")))
            settle()
        }
        assertNull("unasked Exit kiosk prompt on the re-entered kiosk", pinDialog())
    }

    /** Exit mode switched to "remote" (no on-device exit) while the prompt is open; the PIN is unchanged. */
    @Test fun openPromptCannotExitAfterSwitchToRemoteExit() {
        launch(grid)
        tapExitKiosk()
        saveKiosk(grid.copy(exitMode = "remote"))
        settle()
        pinDialog()?.let { submitPin(pin) }
        assertNotNull("kiosk ended on-device although its exit is now remote-only", storedKiosk())
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
    }

    /** Restored PIN prompt (process death) on a kiosk that is now remote-exit only must not come back. */
    @Test fun restoredPromptNotReopenedOnRemoteExitKiosk() {
        launch(grid)
        tapExitKiosk()
        processDeathRestore(grid.copy(exitMode = "remote"))
        assertNull("PIN prompt restored on a kiosk with no on-device exit", pinDialog())
    }

    /** A changed kiosk closes the open prompt (it was for the old one); a new prompt checks the new PIN. */
    @Test fun pinChangedWhileOpenChecksTheNewPin() {
        launch(grid)
        tapExitKiosk()
        saveKiosk(grid.copy(password = "9999"))
        settle()
        assertNull("the prompt for the old kiosk closes", pinDialog())
        tapExitKiosk()
        submitPin(pin)
        assertNotNull(storedKiosk())
        assertNotNull("wrong PIN keeps the dialog", pinDialog())
        submitPin("9999")
        assertNull(storedKiosk())
    }

    @Test fun pinRemovedWhileOpenRefuses() {
        launch(grid)
        tapExitKiosk()
        saveKiosk(grid.copy(password = null))
        settle()
        pinDialog()?.let { submitPin(pin) }
        assertNotNull(storedKiosk())
        assertEquals(0, pinFailures())
    }

    @Test fun kioskSwitchedToSingleWhileDialogOpenStillPinned() {
        launch(grid)
        tapExitKiosk()
        drainStarts()
        saveKiosk(single)
        settle()
        assertEquals(1, pinnedStarts())
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
    }

    // --- Exit ----------------------------------------------------------------------------------------------------

    @Test fun rightPinExitsCleanly() {
        launch(grid)
        tapExitKiosk()
        submitPin(pin)
        assertNull(storedKiosk())
        assertEquals(ActivityManager.LOCK_TASK_MODE_NONE, lockTaskState())
        assertTrue(activity.isFinishing)
        assertEquals(1, events("kioskExit"))
    }

    /**
     * PR #192 re-review S1': right PIN, but the exit's journal entry can't be written (a full disk). The exit doesn't
     * start, so the launcher neither leaves lock task, nor records "exited on-device", nor finishes. The PIN prompt
     * closed on the right PIN, so the launcher says why nothing happened (N1'').
     */
    @Test fun rightPinWithAnUnwritableExitJournalStaysInKiosk() {
        launch(grid)
        exitJournalFull()
        tapExitKiosk()
        submitPin(pin)
        assertEquals(grid, storedKiosk())
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        assertFalse(activity.isFinishing)
        assertEquals(0, events("kioskExit"))
        assertNull("the PIN prompt closed on the right PIN", pinDialog())
        val d = unavailableDialog()
        assertNotNull("silent: the right PIN did nothing visible", d)
        button(d!!.window!!.decorView, s(R.string.action_ok))!!.performClick()
        settle()
        assertNull(unavailableDialog())
        tapExitKiosk()
        assertNotNull("the exit can be tried again", pinDialog())
    }

    /**
     * The right PIN for a kiosk that another kiosk replaced as it ran (EXIT_SUPERSEDED): nothing is said, the new
     * kiosk's delivery redraws the launcher. Every dialog shown counts, not only the last, so one shown and then
     * closed by that delivery fails too.
     */
    @Test fun rightPinForAReplacedKioskShowsNoUnavailableMessage() {
        launch(grid)
        tapExitKiosk()
        val d = pinDialog()!!
        edit(d.window!!.decorView)!!.setText(pin)
        val replacement = grid.copy(allowedPackages = listOf(other))
        saveKiosk(replacement) // the store has the new kiosk; its delivery to the launcher hasn't run yet
        button(d.window!!.decorView, s(R.string.kiosk_exit_confirm))!!.performClick()
        settle()
        assertEquals(replacement, storedKiosk())
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        assertFalse(activity.isFinishing)
        assertTrue(
            "unavailable shown for a superseded exit",
            org.robolectric.shadows.ShadowDialog.getShownDialogs().none(::isUnavailable),
        )
    }

    /** Right PIN, then the activity is destroyed before the exit finished (rotation on polish, system kill). */
    @Test fun exitFinishesEvenIfActivityDestroyedRightAfterPin() {
        launch(grid)
        tapExitKiosk()
        val d = pinDialog()!!
        edit(d.window!!.decorView)!!.setText(pin)
        button(d.window!!.decorView, s(R.string.kiosk_exit_confirm))!!.performClick()
        ctl!!.pause().stop().destroy() // no settle in between
        settle()
        assertNull("kiosk state still saved after a confirmed exit", storedKiosk())
        val alias = android.content.ComponentName(app, "com.mdmesh.agent.KioskHomeAlias")
        assertEquals(
            "HOME claim left enabled after a confirmed exit",
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            app.packageManager.getComponentEnabledSetting(alias),
        )
    }

    @Test fun doubleSubmitRightPinExitsOnce() {
        launch(grid)
        tapExitKiosk()
        val d = pinDialog()!!
        val e = edit(d.window!!.decorView)!!
        val exit = button(d.window!!.decorView, s(R.string.kiosk_exit_confirm))!!
        e.setText(pin)
        exit.performClick()
        // A real second tap / IME Done only reaches the dialog while its window is still attached.
        if (exit.isAttachedToWindow && d.isShowing) exit.performClick()
        if (e.isAttachedToWindow && d.isShowing) e.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
        settle()
        assertEquals(1, events("kioskExit"))
    }

    @Test fun wrongPinBurstCountsEachTryOnceAndLocks() {
        launch(grid)
        tapExitKiosk()
        val d = pinDialog()!!
        val e = edit(d.window!!.decorView)!!
        // A scanner burst: "0000\n" x5, with Enter down+up after each.
        repeat(5) {
            if (pinDialog() == null) return@repeat
            e.setText("0000")
            e.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            e.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            e.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            e.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        settle()
        assertEquals(3, pinFailures())
        assertNotNull(lockedDialog())
        assertEquals(1, events("kioskPinLockout"))
    }

    @Test fun lockoutSurvivesRecreateAndProcessDeath() {
        launch(grid)
        tapExitKiosk()
        repeat(3) { submitPin("0000") }
        assertNotNull(lockedDialog())
        processDeathRestore()
        assertNotNull("Locked comes back after restore", lockedDialog())
        lockedDialog()!!.dismiss(); settle()
        tapExitKiosk()
        assertNotNull("still locked: no PIN prompt", lockedDialog())
        assertNull(pinDialog())
    }

    @Test fun lockoutEndsOnlyAfterElapsedTime() {
        launch(grid)
        tapExitKiosk()
        repeat(3) { submitPin("0000") }
        lockedDialog()!!.dismiss(); settle()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(29))
        tapExitKiosk()
        assertNotNull(lockedDialog())
        lockedDialog()!!.dismiss(); settle()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        tapExitKiosk()
        assertNotNull(pinDialog())
        submitPin("0000")
        assertNotNull("4th wrong PIN locks again at once", lockedDialog())
    }

    @Test fun backKeyOnPinDialogCancelsWithoutATry() {
        launch(grid)
        tapExitKiosk()
        edit(pinDialog()!!.window!!.decorView)!!.setText("0000")
        pinDialog()!!.onBackPressed()
        settle()
        assertNull(pinDialog())
        assertEquals(0, pinFailures())
    }

    @Test fun destroyWithDialogOpenLeavesNoWindow() {
        launch(grid)
        tapExitKiosk()
        val d = pinDialog()!!
        ctl!!.pause().stop().destroy()
        settle()
        assertFalse(d.isShowing)
    }

    @Test fun doubleTapExitKioskShowsOneDialog() {
        launch(grid)
        val b = exitButton()!!
        b.performClick(); b.performClick(); b.performClick()
        settle()
        val showing = org.robolectric.shadows.ShadowDialog.getShownDialogs().count { it.isShowing }
        assertEquals(1, showing)
    }

    @Test fun gestureSixTapsNothingSevenTapsPrompt() {
        launch(grid.copy(exitMode = "gesture"))
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val target = findGestureTarget(root)!!
        repeat(6) { target.performClick() }
        settle()
        assertNull(pinDialog())
        target.performClick()
        settle()
        assertNotNull(pinDialog())
    }

    private fun findGestureTarget(v: android.view.View): android.view.View? = when {
        v.javaClass == android.view.View::class.java && v.hasOnClickListeners() -> v
        v is android.view.ViewGroup ->
            (0 until v.childCount).firstNotNullOfOrNull { findGestureTarget(v.getChildAt(it)) }
        else -> null
    }

    @Test fun remoteExitModeShowsNoAffordance() {
        launch(grid.copy(exitMode = "remote"))
        assertNull(exitButton())
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        assertNull(findGestureTarget(root))
    }

    @Test fun blankPinMeansNoExit() {
        launch(grid.copy(password = "   "))
        assertNull(exitButton())
    }

    // --- Saved state ---------------------------------------------------------------------------------------------

    @Test fun forgedSavedStateOnSingleKioskOpensNoPrompt() {
        val forged = Bundle().apply { putString("exitDialog", "pin") }
        launch(single, forged)
        assertNull(pinDialog())
    }

    @Test fun forgedSavedStateWithoutKioskOpensNothingLater() {
        val forged = Bundle().apply { putString("exitDialog", "pin") }
        launch(null, forged)
        saveKiosk(grid)
        settle()
        assertNull(pinDialog())
    }

    @Test fun lockedRestoredAfterExpiryIsDropped() {
        launch(grid)
        tapExitKiosk()
        repeat(3) { submitPin("0000") }
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(31))
        processDeathRestore()
        assertNull(lockedDialog())
    }

    // --- Intents from other apps -----------------------------------------------------------------------------------

    @Test fun foreignIntentToLauncherDoesNotChangeState() {
        launch(grid)
        val i = Intent(app, KioskLauncherActivity::class.java)
            .setAction("android.intent.action.MAIN")
            .putExtra("exitDialog", "pin")
            .putExtra("android:savedDialogs", Bundle())
        ctl!!.newIntent(i)
        settle()
        assertNull(pinDialog())
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        assertNotNull(storedKiosk())
    }

    @Test fun freshLauncherInstanceWhileInKioskRelocks() {
        launch(grid)
        activity.stopLockTask()
        ctl!!.pause().stop().destroy()
        ctl = Robolectric.buildActivity(KioskLauncherActivity::class.java).setup()
        settle()
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
    }
}
