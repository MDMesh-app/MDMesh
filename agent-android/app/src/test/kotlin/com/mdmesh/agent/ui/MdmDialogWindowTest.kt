package com.mdmesh.agent.ui

import android.view.WindowManager.LayoutParams
import org.junit.Assert.assertEquals
import org.junit.Test

/** The dialog window: width capped at M3's 560dp, the keyboard resizing it, and the icon's baseline lookup. */
class MdmDialogWindowTest {

    @Test fun `width follows the theme fraction, capped`() {
        // Portrait phone: 95% of 1080px. Landscape tablet: 72% of 2560px would be 1843px, capped at 1554px.
        assertEquals(1026, MdmDialog.windowWidth(screenWidthPx = 1080, fraction = 0.95f, maxPx = 1554))
        assertEquals(1554, MdmDialog.windowWidth(screenWidthPx = 2560, fraction = 0.72f, maxPx = 1554))
        assertEquals(1554, MdmDialog.windowWidth(screenWidthPx = 1554, fraction = 1f, maxPx = 1554))
    }

    @Test fun `the keyboard resizes the window, and opens with the PIN dialog`() {
        val plain = MdmDialog.softInputMode(keyboard = false)
        val pin = MdmDialog.softInputMode(keyboard = true)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, plain and LayoutParams.SOFT_INPUT_MASK_ADJUST)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, pin and LayoutParams.SOFT_INPUT_MASK_ADJUST)
        assertEquals(LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE, pin and LayoutParams.SOFT_INPUT_MASK_STATE)
        assertEquals(LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED, plain and LayoutParams.SOFT_INPUT_MASK_STATE)
    }

    @Test fun `first baseline counts the font padding only when the TextView adds it`() {
        // Roboto at 18sp, xxhdpi-ish: top -50, ascent -45 (px).
        assertEquals(50, MdmDialog.firstBaseline(paddingTop = 0, includeFontPadding = true, top = -50, ascent = -45))
        assertEquals(45, MdmDialog.firstBaseline(paddingTop = 0, includeFontPadding = false, top = -50, ascent = -45))
        assertEquals(58, MdmDialog.firstBaseline(paddingTop = 8, includeFontPadding = true, top = -50, ascent = -45))
    }
}
