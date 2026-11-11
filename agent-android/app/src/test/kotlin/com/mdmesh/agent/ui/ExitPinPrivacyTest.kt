package com.mdmesh.agent.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import com.mdmesh.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Show PIN reveals the exit PIN to read, nothing more: no copy, cut, share or translate, no selection toolbar, no
 * autofill or keyboard learning, on API 24-35. The clipboard never sees it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 33, 34, 35], application = Application::class)
class ExitPinPrivacyTest {

    private lateinit var field: android.widget.EditText
    private val clipboard get() =
        DialogHost.showing().context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Before fun openShown() {
        DialogSlot("test").show("pin", ExitPinDialog.create(DialogHost.activity()) { _, _ -> }.dialog)
        DialogHost.idle()
        val dialog = DialogHost.showing()
        field = DialogHost.field(dialog)
        field.setText("4821")
        dialog.findViewById<View>(R.id.exit_pin_toggle).performClick()
        DialogHost.idle()
        assertEquals("the PIN is shown", "4821", field.layout.text.toString())
        field.requestFocus()
        field.selectAll()
    }

    private fun can(method: String): Boolean = ReflectionHelpers.callInstanceMethod<Boolean>(field, method)

    @Test fun `no copy, cut or share is offered`() {
        assertFalse(can("canCopy"))
        assertFalse(can("canCut"))
        if (android.os.Build.VERSION.SDK_INT >= 23) assertFalse(can("canShare"))
    }

    @Test fun `text actions and Ctrl+C never reach the clipboard`() {
        for (id in listOf(android.R.id.copy, android.R.id.cut, android.R.id.shareText)) field.onTextContextMenuItem(id)
        // Ctrl+C and Ctrl+X reach a focused TextView as key shortcuts.
        for (key in listOf(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_X)) {
            field.onKeyShortcut(key, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 0, KeyEvent.META_CTRL_ON))
        }
        assertNull("clipboard got ${clipboard.primaryClip}", clipboard.primaryClip?.getItemAt(0)?.text)
        assertEquals("cut must not remove it either", "4821", field.text.toString())
    }

    /**
     * Long press, double tap or a tap on the cursor start TextView's floating action mode through these callbacks.
     * Robolectric doesn't create floating modes, so this checks what the field ships with: not long-clickable, and
     * the callbacks TextView must ask refuse.
     */
    @Test fun `no selection or insertion toolbar`() {
        assertFalse(field.isLongClickable)
        assertFalse(field.customSelectionActionModeCallback!!.onCreateActionMode(null, null))
        assertFalse(field.customInsertionActionModeCallback!!.onCreateActionMode(null, null))
    }

    @Test fun `no autofill, suggestions or keyboard learning`() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, field.importantForAutofill)
        }
        assertTrue(field.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertTrue(field.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(field as TextView, "isSuggestionsEnabled"))
    }
}
