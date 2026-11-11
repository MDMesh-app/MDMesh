package com.mdmesh.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The saved-state half of DialogSlot: which dialog to reopen after a recreation, handed back exactly once. */
class DialogSlotTest {

    @Test fun `a restored tag is handed back once`() {
        val slot = DialogSlot("k")
        slot.restoreTag("pin")
        assertEquals("pin", slot.takeRestored())
        assertNull(slot.takeRestored())
    }

    @Test fun `a tag not yet taken survives another recreation`() {
        val slot = DialogSlot("k")
        slot.restoreTag("pin")
        assertEquals("pin", slot.savedTag())
    }

    @Test fun `a taken tag is not saved again`() {
        val slot = DialogSlot("k")
        slot.restoreTag("pin")
        slot.takeRestored()
        assertNull("dropped or reopened, it must not come back after the next recreation", slot.savedTag())
    }

    @Test fun `nothing open, nothing saved`() {
        assertNull(DialogSlot("k").savedTag())
    }
}
