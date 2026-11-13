package com.mdmesh.agent

import com.mdmesh.proto.KioskApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class KioskDeliveryTest {

    private val single =
        KioskApplyPayload(mode = "single", pinPackage = "com.example.app", allowedPackages = listOf("com.example.app"))
    private val grid = KioskApplyPayload(mode = "launcher", allowedPackages = listOf("a", "b"))

    @Test fun `no kiosk is idle`() {
        assertEquals(KioskDelivery.IDLE, KioskDelivery.of(null, null))
        assertEquals(KioskDelivery.IDLE, KioskDelivery.of(single, null))
    }

    @Test fun `a first or a different kiosk is applied`() {
        assertEquals(KioskDelivery.APPLY, KioskDelivery.of(null, single))
        assertEquals(KioskDelivery.APPLY, KioskDelivery.of(grid, single))
        assertEquals(KioskDelivery.APPLY, KioskDelivery.of(single, single.copy(pinPackage = "com.example.other")))
    }

    @Test fun `the same single-app kiosk again only keeps the pin, so the app opens once per return`() {
        // Back to HOME: onResume reopens the app; the restarted collector delivering the same kiosk must not too.
        assertEquals(KioskDelivery.PIN_ONLY, KioskDelivery.of(single, single.copy()))
    }

    @Test fun `the same grid again is redrawn, so newly installed apps appear`() {
        assertEquals(KioskDelivery.APPLY, KioskDelivery.of(grid, grid.copy()))
    }

    @Test fun `single mode without a package shows the grid, so it is applied again`() {
        val noPin = single.copy(pinPackage = null)
        assertEquals(KioskDelivery.APPLY, KioskDelivery.of(noPin, noPin.copy()))
    }
}
