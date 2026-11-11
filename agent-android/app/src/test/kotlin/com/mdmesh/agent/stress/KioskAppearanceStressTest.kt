package com.mdmesh.agent.stress

import com.mdmesh.agent.R
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskBackgroundImageDto
import com.mdmesh.proto.KioskThemeDto
import org.junit.Assert.assertEquals
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
}
