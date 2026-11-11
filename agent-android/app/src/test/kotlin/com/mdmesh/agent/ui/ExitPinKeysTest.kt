package com.mdmesh.agent.ui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.mdmesh.agent.ui.ExitPinDialog.EditorAction.CONSUME
import com.mdmesh.agent.ui.ExitPinDialog.EditorAction.IGNORE
import com.mdmesh.agent.ui.ExitPinDialog.EditorAction.SUBMIT
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keys that submit the exit PIN: the soft keyboard's Done, and Enter on a hardware keyboard or a scanner (it
 * arrives as IME_NULL with the key event, once down and once up). One press is one try: only the down submits.
 */
class ExitPinKeysTest {

    private fun of(actionId: Int, keyAction: Int? = null, keyCode: Int? = null) =
        ExitPinDialog.editorAction(actionId, keyAction, keyCode)

    @Test fun `soft keyboard Done submits`() {
        assertEquals(SUBMIT, of(EditorInfo.IME_ACTION_DONE))
    }

    @Test fun `hardware Enter submits once, on the press`() {
        assertEquals(SUBMIT, of(EditorInfo.IME_NULL, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals(SUBMIT, of(EditorInfo.IME_NULL, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(CONSUME, of(EditorInfo.IME_NULL, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    @Test fun `anything else is left to the field`() {
        assertEquals(IGNORE, of(EditorInfo.IME_NULL))
        assertEquals(IGNORE, of(EditorInfo.IME_ACTION_NEXT))
        assertEquals(IGNORE, of(EditorInfo.IME_NULL, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
    }
}
