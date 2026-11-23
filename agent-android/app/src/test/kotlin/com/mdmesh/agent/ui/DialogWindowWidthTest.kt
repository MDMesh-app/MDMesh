package com.mdmesh.agent.ui

import android.app.Application
import android.util.TypedValue
import android.view.ViewGroup
import com.mdmesh.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * The real dialog windows after DialogSlot.show: AppCompat's share of the screen (95% portrait, 65% landscape on a
 * phone), never more than 560dp of card (plus the 16dp window inset each side), whatever the text length.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 33, 34, 35], application = Application::class)
class DialogWindowWidthTest {

    private val long = "This kiosk has no exit on the device. Once it locks, only an admin in the MDMesh console " +
        "can unlock it, and only while the device is online. ".repeat(3)

    private fun shownWidths(): List<Pair<String, Int>> {
        val activity = DialogHost.activity()
        val slot = DialogSlot("test")
        val specs = listOf(
            "return" to MdmDialog.Spec(
                title = "Return to kiosk?", confirm = "Return", message = long, cancel = "Cancel",
            ),
            "no-exit" to MdmDialog.Spec(
                title = "Lock with no way out here?", confirm = "Lock anyway", message = long, cancel = "Cancel",
                icon = R.drawable.ic_v2_lock, danger = true,
            ),
            "locked" to MdmDialog.Spec(title = "Exit locked", confirm = "OK", message = "Try again in 30 s."),
        )
        val widths = specs.map { (name, spec) ->
            slot.show(name, MdmDialog.create(activity, spec) {})
            DialogHost.idle()
            name to DialogHost.showing().window!!.attributes.width
        }
        slot.show("pin", ExitPinDialog.create(activity) { _, _ -> }.dialog)
        DialogHost.idle()
        return widths + ("pin" to DialogHost.showing().window!!.attributes.width)
    }

    private fun px(dp: Int) = (dp * RuntimeEnvironment.getApplication().resources.displayMetrics.density).roundToInt()

    private val maxWindow get() = px(560) + 2 * px(16)

    private fun screenShare(attr: Int): Int {
        val res = RuntimeEnvironment.getApplication().resources
        val value = TypedValue()
        val theme = res.newTheme().apply { applyStyle(R.style.ThemeOverlay_MdmAgent_Dialog, true) }
        theme.resolveAttribute(attr, value, true)
        return (res.displayMetrics.widthPixels * value.getFraction(1f, 1f)).roundToInt()
    }

    private fun assertAll(expected: Int) {
        for ((name, width) in shownWidths()) {
            assertTrue("$name: window width must be set, was $width", width != ViewGroup.LayoutParams.WRAP_CONTENT)
            assertEquals(name, expected, width)
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-420dpi")
    fun `phone portrait - the theme's share of the screen`() =
        assertAll(screenShare(android.R.attr.windowMinWidthMinor))

    @Test @Config(qualifiers = "w891dp-h411dp-land-420dpi")
    fun `phone landscape - the theme's share, under the cap`() =
        assertAll(minOf(screenShare(android.R.attr.windowMinWidthMajor), maxWindow))

    @Test @Config(qualifiers = "w1000dp-h450dp-land-420dpi")
    fun `wide landscape - capped at 560dp of card`() = assertAll(maxWindow)

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun `tablet landscape - never wider than the cap`() =
        assertAll(minOf(screenShare(android.R.attr.windowMinWidthMajor), maxWindow))
}
