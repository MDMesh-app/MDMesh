package com.mdmesh.agent.ui

import android.view.KeyEvent
import com.mdmesh.agent.ui.ExitPinDialog.Input.CONSUME
import com.mdmesh.agent.ui.ExitPinDialog.Input.IGNORE
import com.mdmesh.agent.ui.ExitPinDialog.Input.SUBMIT
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hardware-key rule on its own (ExitPinInputTest runs it through the real TextView on API 24-35). */
class ExitPinKeysTest {

    @Test fun `Enter submits on its first press only`() {
        for (code in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            assertEquals(SUBMIT, ExitPinDialog.keyAction(code, KeyEvent.ACTION_DOWN, 0))
            assertEquals(CONSUME, ExitPinDialog.keyAction(code, KeyEvent.ACTION_DOWN, 1))
            assertEquals(CONSUME, ExitPinDialog.keyAction(code, KeyEvent.ACTION_UP, 0))
            assertEquals(CONSUME, ExitPinDialog.keyAction(code, KeyEvent.ACTION_MULTIPLE, 0))
        }
    }

    @Test fun `other keys are the field's`() {
        assertEquals(IGNORE, ExitPinDialog.keyAction(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0))
        assertEquals(IGNORE, ExitPinDialog.keyAction(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_DOWN, 0))
    }
}
