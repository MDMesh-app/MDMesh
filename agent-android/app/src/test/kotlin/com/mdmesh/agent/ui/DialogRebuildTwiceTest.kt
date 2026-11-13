package com.mdmesh.agent.ui

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** two look-changing config changes before the rebuilt prompt's (posted) OnShow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "notnight")
class DialogRebuildTwiceTest {
    private val activity = DialogHost.activity()
    private val slot = DialogSlot("test")

    private fun openPin(typed: CharSequence? = null) =
        slot.show("pin", ExitPinDialog.create(activity, typed) { _, _ -> }.dialog)

    private fun changed(edit: Configuration.() -> Unit) = Configuration(activity.resources.configuration).apply(edit)

    @Test fun `two quick rebuilds keep the deadline`() {
        openPin()
        DialogHost.idle()
        DialogHost.field(DialogHost.showing()).setText("48")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        val night = changed {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val bigger = Configuration(night).apply { fontScale *= 1.3f }
        slot.onConfigurationChanged(night) { _, typed -> openPin(typed) }
        // second change in the same looper turn: before the rebuilt dialog's OnShow message ran
        slot.onConfigurationChanged(bigger) { _, typed -> openPin(typed) }
        DialogHost.idle()
        assertTrue("rebuilt", ShadowDialog.getLatestDialog()?.isShowing == true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25))
        assertFalse("PIN outlived its minute", ShadowDialog.getLatestDialog()?.isShowing == true)
    }
}
