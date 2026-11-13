package com.mdmesh.agent.ui

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The prompt closes after a minute without input, or after the longer "Time to take action" a user set in the
 * accessibility settings (API 29+), never sooner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35], application = Application::class)
class ExitPinIdleTimeoutTest {

    private fun open(): android.app.Dialog {
        DialogSlot("test").show("pin", ExitPinDialog.create(DialogHost.activity()) { _, _ -> }.dialog)
        DialogHost.idle()
        return DialogHost.showing()
    }

    private fun after(seconds: Long) = ShadowLooper.idleMainLooper(seconds, TimeUnit.SECONDS)

    @Test fun `a minute without input closes it`() {
        val dialog = open()
        after(59)
        assertTrue(dialog.isShowing)
        after(2)
        assertFalse(dialog.isShowing)
    }

    @Test @Config(sdk = [30, 35])
    fun `a longer accessibility timeout is honoured`() {
        val a11y = DialogHost.activity().getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(a11y).setInteractiveUiTimeout(120_000)
        val dialog = open()
        after(100)
        assertTrue("closed before the user's 2-minute accessibility timeout", dialog.isShowing)
        after(21)
        assertFalse(dialog.isShowing)
    }
}
