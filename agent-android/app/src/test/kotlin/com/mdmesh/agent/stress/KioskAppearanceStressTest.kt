package com.mdmesh.agent.stress

import com.mdmesh.agent.R
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskBackgroundImageDto
import com.mdmesh.proto.KioskThemeDto
import android.app.ActivityManager
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A kiosk's appearance (colours, icon size, background image, fit, focal point) changes in place: never a re-entry,
 * no relaunch of the pinned app, no crash-loop count. Its identity (apps, mode, exit, features) re-enters as before.
 */
class KioskAppearanceStressTest : KioskHarness() {

    private val sha = "b".repeat(64)

    private fun styled(k: KioskApplyPayload, theme: KioskThemeDto) = k.copy(theme = theme)

    private fun change(next: KioskApplyPayload) {
        saveKiosk(next)
        settle()
    }

    @Test fun appearanceChangesNeverRelaunchThePinnedApp() {
        launch(single)
        assertEquals("opened once on entry", 1, pinnedStarts())
        val looks = listOf(
            KioskThemeDto(backgroundColor = "#00676B", textColor = "#FFFFFF"),
            KioskThemeDto(backgroundColor = "#00676B", textColor = "#FFFFFF", iconSize = "LARGE"),
            KioskThemeDto(backgroundImage = KioskBackgroundImageDto("https://127.0.0.1:1/a.png", sha)),
            KioskThemeDto(backgroundImage = KioskBackgroundImageDto("https://127.0.0.1:1/a.png", sha, fit = "fit")),
            KioskThemeDto(backgroundImage = KioskBackgroundImageDto("https://127.0.0.1:1/a.png", sha, focalX = 0.1)),
        )
        for (look in looks) {
            change(styled(single, look))
            assertEquals("$look: no relaunch", 0, pinnedStarts())
        }
        assertEquals("no crash-loop events", 0, events("kioskCrashLoop"))
    }

    @Test fun aRecolouredHoldStaysAHoldInTheNewColours() {
        launch(single)
        bounce(4)
        assertTrue(holdShowing())
        drainStarts()
        change(styled(single, KioskThemeDto(backgroundColor = "#FFFFFF")))
        assertTrue("still holding", holdShowing())
        assertEquals(0, pinnedStarts())
        val title = findText(decor, s(R.string.kiosk_hold_title))!!
        assertEquals("drawn in the new palette", 0xFF1B2230.toInt(), title.currentTextColor)
    }

    @Test fun aChangedAppListStillReEnters() {
        launch(single)
        drainStarts()
        change(single.copy(allowedPackages = listOf(pinned, other)))
        assertEquals("the kiosk changed: opened again", 1, pinnedStarts())
    }

    @Test fun aUrlOnlyChangeReachesTheLauncher() {
        val sha = "c".repeat(64)
        fun at(url: String) = single.copy(theme = KioskThemeDto(backgroundImage = KioskBackgroundImageDto(url, sha)))
        launch(at("https://old.invalid/a.png"))
        saveKiosk(at("https://new.invalid/a.png"))
        settle()
        val f = activity.javaClass.getDeclaredField("active").apply { isAccessible = true }
        val active = f.get(activity) as KioskApplyPayload
        val url = active.theme.backgroundImage!!.url
        assertEquals("the launcher fetches from the new URL", "https://new.invalid/a.png", url)
        assertEquals("and doesn't reopen the app for it", 1, pinnedStarts())
    }

    @Test fun anAppearanceChangeUnderAnOpenPinPromptKeepsItAndTheKiosk() {
        launch(single)
        drainStarts()
        tapExitKiosk()
        val d = pinDialog()
        assertNotNull(d)
        edit(d!!.window!!.decorView)!!.setText("48")
        change(styled(single, KioskThemeDto(backgroundColor = "#FFFFFF", textColor = "#000000")))
        val now = pinDialog()
        assertNotNull("prompt still open", now)
        assertEquals("typed PIN kept", "48", edit(now!!.window!!.decorView)!!.text.toString())
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        assertEquals(0, pinnedStarts())
        submitPin("0000")
        assertEquals("a wrong PIN is still refused", ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        assertTrue("still in kiosk", storedKiosk() != null)
        tapExitKiosk()
        assertNotNull(pinDialog())
        change(single.copy(password = "9999"))
        assertEquals("a new PIN (identity) closes the prompt", null, pinDialog())
    }

    @Test fun anAppearanceChangeAndRotationInOneTurnDuringAHold() {
        launch(single)
        bounce(4)
        assertTrue(holdShowing())
        drainStarts()
        saveKiosk(styled(single, KioskThemeDto(backgroundColor = "#00676B", iconSize = "LARGE")))
        val rotated = Configuration(activity.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            screenWidthDp = 1280
            screenHeightDp = 800
        }
        ctl!!.configurationChange(rotated)
        settle()
        assertTrue("still holding", holdShowing())
        assertEquals(0, pinnedStarts())
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED, lockTaskState())
        coverAndReturn { saveKiosk(styled(single, KioskThemeDto(backgroundColor = "#FFFFFF"))) }
        assertTrue("still holding after a restyle while stopped", holdShowing())
        assertEquals(0, pinnedStarts())
    }

    @Test fun appearanceChangesWhileThePinnedAppIsInFrontCountEachReturnOnce() {
        launch(single)
        drainStarts()
        for (c in listOf("#111111", "#222222", "#333333")) {
            coverAndReturn { saveKiosk(styled(single, KioskThemeDto(backgroundColor = c))) }
            assertEquals("$c: reopened exactly once", 1, pinnedStarts())
            assertTrue(!holdShowing())
        }
        coverAndReturn { saveKiosk(styled(single, KioskThemeDto(backgroundColor = "#444444"))) }
        assertTrue("the 4th return in a row still trips the hold", holdShowing())
        assertEquals(0, pinnedStarts())
    }
}
