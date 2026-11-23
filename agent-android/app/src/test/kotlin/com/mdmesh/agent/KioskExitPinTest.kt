package com.mdmesh.agent

import com.mdmesh.proto.KioskApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F1: the PIN the exit is checked against is the current kiosk's, and only while it has an on-device exit. */
class KioskExitPinTest {

    private val kiosk = KioskApplyPayload(exitMode = "visible", password = "4821")

    @Test fun `visible and gesture exits check the kiosk's PIN`() {
        assertEquals("4821", KioskExitDialogs.onDevicePin(kiosk))
        assertEquals("4821", KioskExitDialogs.onDevicePin(kiosk.copy(exitMode = "gesture")))
    }

    @Test fun `remote exit mode has no on-device PIN, even with a password set`() {
        assertNull(KioskExitDialogs.onDevicePin(kiosk.copy(exitMode = "remote")))
    }

    @Test fun `no kiosk or no PIN means no on-device exit`() {
        assertNull(KioskExitDialogs.onDevicePin(null))
        assertNull(KioskExitDialogs.onDevicePin(kiosk.copy(password = null)))
        assertNull(KioskExitDialogs.onDevicePin(kiosk.copy(password = " ")))
    }
}
