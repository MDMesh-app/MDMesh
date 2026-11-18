package com.mdmesh.agent.ui

import android.app.Application
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import com.mdmesh.agent.R
import com.mdmesh.kiosk.ExitPinFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The exit PIN's keyboard: a number pad for a PIN of digits (every PIN set since the 6-to-12-digit rule), the full
 * keyboard for a PIN saved as other text before it, so such a kiosk can still be left on the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35], application = Application::class)
class ExitPinKeyboardTest {

    private val submitted = mutableListOf<String>()

    private fun open(numeric: Boolean): EditText {
        DialogSlot("test").show(
            "pin",
            ExitPinDialog.create(DialogHost.activity(), numeric = numeric) { entered, _ ->
                submitted += entered
            }.dialog,
        )
        DialogHost.idle()
        return DialogHost.field(DialogHost.showing()).also { it.requestFocus() }
    }

    private fun EditText.isPassword(): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> false
        }
    }

    @Test fun `a PIN of digits gets a number pad, still a password`() {
        val field = open(numeric = true)
        assertEquals(InputType.TYPE_CLASS_NUMBER, field.inputType and InputType.TYPE_MASK_CLASS)
        assertTrue(field.isPassword())
        val info = EditorInfo()
        field.onCreateInputConnection(info)
        assertEquals(InputType.TYPE_CLASS_NUMBER, info.inputType and InputType.TYPE_MASK_CLASS)
    }

    @Test fun `a legacy PIN keeps the full keyboard`() {
        val field = open(numeric = false)
        assertEquals(InputType.TYPE_CLASS_TEXT, field.inputType and InputType.TYPE_MASK_CLASS)
        assertTrue(field.isPassword())
    }

    @Test fun `showing the PIN on the number pad keeps it a number password`() {
        val field = open(numeric = true)
        val toggle = DialogHost.showing().findViewById<ImageButton>(R.id.exit_pin_toggle)
        toggle.performClick()
        toggle.performClick()
        toggle.performClick()
        assertEquals(InputType.TYPE_CLASS_NUMBER, field.inputType and InputType.TYPE_MASK_CLASS)
        assertTrue(field.isPassword())
    }

    @Test fun `a scanner's digits and Enter submit once on the number pad`() {
        val field = open(numeric = true)
        field.onCreateInputConnection(EditorInfo())!!.commitText("482139\r\n", 1)
        DialogHost.idle()
        assertEquals("482139", field.text.toString())
        assertEquals(listOf("482139"), submitted)
    }

    @Test fun `the number pad takes no letters, whatever an IME sends`() {
        val field = open(numeric = true)
        field.onCreateInputConnection(EditorInfo())!!.commitText("48a2b1", 1)
        DialogHost.idle()
        assertFalse(field.text.toString().any { it.isLetter() })
    }

    @Test fun `which PINs get the number pad`() {
        assertTrue(ExitPinFormat.numeric("482139"))
        assertTrue(ExitPinFormat.numeric("0000"))
        assertTrue("full-width digits normalize to digits", ExitPinFormat.numeric("４８２１"))
        assertFalse(ExitPinFormat.numeric(null))
        assertFalse(ExitPinFormat.numeric(""))
        assertFalse(ExitPinFormat.numeric("12 34"))
        assertFalse(ExitPinFormat.numeric("letmein"))
        assertFalse(ExitPinFormat.numeric("1234a"))
        assertFalse("other scripts' digits aren't on the pad", ExitPinFormat.numeric("١٢٣٤"))
    }
}
