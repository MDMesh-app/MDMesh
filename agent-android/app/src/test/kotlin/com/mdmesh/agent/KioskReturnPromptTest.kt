package com.mdmesh.agent

import com.mdmesh.proto.KioskApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskReturnPromptTest {

    @Test fun `a kiosk with an exit PIN asks to return and names the PIN`() {
        val prompt = KioskReturnPrompt.of(KioskApplyPayload(password = "1234", exitMode = "gesture"))
        assertEquals(R.string.kiosk_return_action, prompt.confirm)
        assertFalse(prompt.danger)
        // OfflineKioskReturnTest finds the dialog and its button by these texts.
        assertEquals("Return to kiosk?", StringsXml["kiosk_return_title"])
        assertEquals("Return to kiosk", StringsXml["kiosk_return_action"])
        val message = StringsXml["kiosk_return_message"]
        assertEquals(R.string.kiosk_return_message, prompt.message)
        assertTrue(message, message.contains("exit PIN"))
    }

    @Test fun `a kiosk with no exit on the device says only the console unlocks it`() {
        for (kiosk in listOf(
            KioskApplyPayload(password = "1234", exitMode = "remote"),
            KioskApplyPayload(password = null, exitMode = "visible"),
        )) {
            val prompt = KioskReturnPrompt.of(kiosk)
            assertEquals(R.string.kiosk_return_no_exit_confirm, prompt.confirm)
            assertEquals(R.string.kiosk_return_no_exit_title, prompt.title)
            assertEquals(R.string.kiosk_return_no_exit_message, prompt.message)
            assertTrue(prompt.danger)
        }
        assertEquals("Lock anyway", StringsXml["kiosk_return_no_exit_confirm"])
        val message = StringsXml["kiosk_return_no_exit_message"]
        assertTrue(message, message.contains("only an admin in the MDMesh console"))
    }
}
