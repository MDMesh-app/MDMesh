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

/** A [SlotDialog] that records what the slot did to it. */
private class FakeDialog : SlotDialog {
    var showing = false
    private var onDismiss: (() -> Unit)? = null

    override fun show(key: DialogSlot.Key) {
        showing = true
    }

    override fun dismiss() {
        if (showing) onDismiss?.invoke()
        showing = false
    }

    override fun setOnDismiss(listener: () -> Unit) {
        onDismiss = listener
    }
}

class DialogSlotShowingTest {

    @Test fun `shows the dialog under its tag, closing the one before`() {
        val slot = DialogSlot("k")
        val first = FakeDialog()
        val second = FakeDialog()
        slot.show("pin", first)
        slot.show("locked", second)
        assertEquals(false, first.showing)
        assertEquals(true, second.showing)
        assertEquals("locked", slot.savedTag())
        assertEquals(second, slot.showing)
    }

    @Test fun `a dialog closed by the user is no longer saved`() {
        val slot = DialogSlot("k")
        val dialog = FakeDialog()
        slot.show("pin", dialog)
        dialog.dismiss()
        assertNull(slot.showing)
        assertNull(slot.savedTag())
    }

    @Test fun `a config change rebuilds the open dialog from current state`() {
        val slot = DialogSlot("k")
        val old = FakeDialog()
        val rebuilt = FakeDialog()
        slot.show("pin", old)
        val reopened = mutableListOf<String>()
        slot.onConfigurationChanged { tag ->
            reopened += tag
            slot.show(tag, rebuilt)
        }
        assertEquals(listOf("pin"), reopened)
        assertEquals(false, old.showing)
        assertEquals(true, rebuilt.showing)
        assertEquals(rebuilt, slot.showing)
    }

    @Test fun `a config change with no dialog open opens nothing`() {
        val slot = DialogSlot("k")
        slot.restoreTag("pin") // restored but not taken yet: the next applied state decides, not the config change
        var calls = 0
        slot.onConfigurationChanged { calls++ }
        assertEquals(0, calls)
    }
}
