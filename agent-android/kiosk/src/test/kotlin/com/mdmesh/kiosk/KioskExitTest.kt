package com.mdmesh.kiosk

import com.mdmesh.proto.KioskApplyPayload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskExitTest {
    private val withPin = KioskApplyPayload(password = "1234")

    @Test fun `a PIN and a button or the corner gesture leave on the device`() {
        assertTrue(KioskExit.onDevice(withPin.copy(exitMode = "visible")))
        assertTrue(KioskExit.onDevice(withPin.copy(exitMode = "gesture")))
    }

    @Test fun `remote exit, an unknown mode or no PIN has no exit on the device`() {
        assertFalse(KioskExit.onDevice(withPin.copy(exitMode = "remote")))
        assertFalse(KioskExit.onDevice(withPin.copy(exitMode = "something-new")))
        assertFalse(KioskExit.onDevice(KioskApplyPayload(exitMode = "visible")))
        assertFalse(KioskExit.onDevice(KioskApplyPayload(exitMode = "gesture", password = "  ")))
    }
}
