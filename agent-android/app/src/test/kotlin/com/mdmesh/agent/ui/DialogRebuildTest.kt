package com.mdmesh.agent.ui

import android.app.Application
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A config change the activity handles itself rebuilds the open dialog when it changes how it looks (dark mode,
 * font or display size), carrying the typed PIN over in memory; one that doesn't (a desk dock) leaves it alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35], application = Application::class, qualifiers = "notnight")
class DialogRebuildTest {

    private val activity = DialogHost.activity()
    private val slot = DialogSlot("test")

    private fun openPin(typed: CharSequence? = null) =
        slot.show("pin", ExitPinDialog.create(activity, typed) { _, _ -> }.dialog)

    private fun changed(edit: Configuration.() -> Unit) = Configuration(activity.resources.configuration).apply(edit)

    private fun mode(uiMode: Int, mask: Int, value: Int) = (uiMode and mask.inv()) or value

    private fun rebuild(config: Configuration): List<Pair<String, CharSequence?>> {
        val calls = mutableListOf<Pair<String, CharSequence?>>()
        slot.onConfigurationChanged(config) { tag, typed ->
            calls += tag to typed
            openPin(typed)
        }
        DialogHost.idle()
        return calls
    }

    @Test fun `dark mode rebuilds the PIN dialog with what was typed`() {
        openPin()
        DialogHost.idle()
        val before = DialogHost.showing()
        DialogHost.field(before).setText("48")
        val night = changed { uiMode = mode(uiMode, Configuration.UI_MODE_NIGHT_MASK, Configuration.UI_MODE_NIGHT_YES) }
        val calls = rebuild(night)
        assertEquals(listOf("pin" to "48"), calls.map { it.first to it.second?.toString() })
        val after = DialogHost.showing()
        assertNotSame(before, after)
        assertEquals("48", DialogHost.field(after).text.toString())
    }

    @Test fun `a bigger font rebuilds it`() {
        openPin()
        DialogHost.idle()
        assertEquals(1, rebuild(changed { fontScale *= 1.3f }).size)
    }

    @Test fun `a desk dock alone doesn't`() {
        openPin()
        DialogHost.idle()
        val before = DialogHost.showing()
        val desk = changed { uiMode = mode(uiMode, Configuration.UI_MODE_TYPE_MASK, Configuration.UI_MODE_TYPE_DESK) }
        val calls = rebuild(desk)
        assertEquals(0, calls.size)
        assertSame(before, DialogHost.showing())
    }
}
