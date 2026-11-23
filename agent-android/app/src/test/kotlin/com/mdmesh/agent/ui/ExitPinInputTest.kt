package com.mdmesh.agent.ui

import android.app.Application
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What submits the exit PIN, through the real TextView key and IME paths. TextView hands the listener IME_NULL with
 * a hardware Enter before API 34 and the field's IME action (Done) from 34 on; both must give one submit per press.
 * The submit callback here keeps the field, so a repeat or a release that submitted again would show up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 33, 34, 35], application = Application::class)
class ExitPinInputTest {

    private val submitted = mutableListOf<String>()
    private lateinit var field: android.widget.EditText

    @Before fun open() {
        val activity = DialogHost.activity()
        DialogSlot("test").show("pin", ExitPinDialog.create(activity) { entered, _ -> submitted += entered }.dialog)
        DialogHost.idle()
        field = DialogHost.field(DialogHost.showing())
        field.requestFocus()
    }

    private fun key(action: Int, code: Int = KeyEvent.KEYCODE_ENTER, repeat: Int = 0) =
        field.dispatchKeyEvent(KeyEvent(0L, 0L, action, code, repeat))

    @Test fun `hardware Enter submits once, held or not`() {
        field.setText("0000")
        key(KeyEvent.ACTION_DOWN)
        key(KeyEvent.ACTION_DOWN, repeat = 1)
        key(KeyEvent.ACTION_DOWN, repeat = 2)
        key(KeyEvent.ACTION_UP)
        DialogHost.idle()
        assertEquals(listOf("0000"), submitted)
    }

    @Test fun `numpad Enter submits once`() {
        field.setText("0000")
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NUMPAD_ENTER)
        DialogHost.idle()
        assertEquals(listOf("0000"), submitted)
    }

    @Test fun `soft keyboard Done submits once`() {
        field.setText("0000")
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        DialogHost.idle()
        assertEquals(listOf("0000"), submitted)
    }

    @Test fun `Enter on an empty field is not a try`() {
        key(KeyEvent.ACTION_DOWN)
        key(KeyEvent.ACTION_UP)
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        DialogHost.idle()
        assertEquals(emptyList<String>(), submitted)
    }

    @Test fun `a scanner's trailing newline submits once and never lands in the PIN`() {
        val ic = field.onCreateInputConnection(EditorInfo())
        ic.commitText("4821\r\n", 1)
        DialogHost.idle()
        assertEquals("4821", field.text.toString())
        assertEquals(listOf("4821"), submitted)
    }

    @Test fun `a newline inside pasted text is dropped, without a submit`() {
        val ic = field.onCreateInputConnection(EditorInfo())
        ic.commitText("48\n21", 1)
        DialogHost.idle()
        assertEquals("4821", field.text.toString())
        assertEquals(emptyList<String>(), submitted)
    }

    /** Closed (Cancel here), the prompt leaves no timer behind holding it and the activity. */
    @Test fun `the idle timer goes with the dialog`() {
        field.setText("12")
        DialogHost.showing().cancel()
        DialogHost.idle()
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        looper.idleFor(java.time.Duration.ofSeconds(1))
        val next = looper.nextScheduledTaskTime
        assertEquals("a task is still scheduled at $next", java.time.Duration.ZERO, next)
    }

    /**
     * A submit already posted (a scanner's text newline) when the prompt closes in the same message, its text still
     * in the field: it must not reach the PIN check.
     */
    @Test fun `a submit pending when the prompt closes does nothing`() {
        field.onCreateInputConnection(EditorInfo())!!.commitText("1234\r", 1)
        DialogHost.showing().cancel()
        DialogHost.idle()
        assertEquals("1234", field.text.toString())
        assertEquals(emptyList<String>(), submitted)
    }
}
