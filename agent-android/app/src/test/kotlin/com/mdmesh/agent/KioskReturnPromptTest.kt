package com.mdmesh.agent

import com.mdmesh.proto.KioskApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskReturnPromptTest {

    @Test fun `a kiosk with an exit PIN asks to return and names the PIN`() {
        val prompt = KioskReturnPrompt.of(KioskApplyPayload(password = "1234", exitMode = "gesture"))
        assertEquals("Return to kiosk", prompt.confirm)
        assertTrue(prompt.message, prompt.message.contains("exit PIN"))
    }

    @Test fun `a kiosk with no exit on the device says only the console unlocks it`() {
        for (kiosk in listOf(
            KioskApplyPayload(password = "1234", exitMode = "remote"),
            KioskApplyPayload(password = null, exitMode = "visible"),
        )) {
            val prompt = KioskReturnPrompt.of(kiosk)
            assertEquals("Lock anyway", prompt.confirm)
            assertTrue(prompt.message, prompt.message.contains("only an admin in the MDMesh console"))
        }
    }
}
