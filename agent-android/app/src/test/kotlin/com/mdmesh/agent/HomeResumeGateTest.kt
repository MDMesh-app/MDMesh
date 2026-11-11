package com.mdmesh.agent

import com.mdmesh.agent.HomeResumeGate.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The kiosk HOME acts once per return to the front, whichever comes first: onResume or the saved kiosk the restarted
 * collector delivers (DataStore delivers after onResume, a synchronous store before it).
 */
class HomeResumeGateTest {

    private val gate = HomeResumeGate()

    @Test fun `return, delivery after resume - the same single kiosk is reopened once, by the delivery`() {
        gate.onStart()
        assertEquals(Step.WAIT, gate.onResume())
        assertEquals(Step.RETURN, gate.onDelivery(KioskDelivery.PIN_ONLY))
    }

    @Test fun `return, delivery before resume - reopened once, by onResume`() {
        gate.onStart()
        assertEquals(Step.NONE, gate.onDelivery(KioskDelivery.PIN_ONLY))
        assertEquals(Step.RETURN, gate.onResume())
    }

    @Test fun `kiosk changed while HOME was behind the app - only the new kiosk opens`() {
        // The old pinned app must not be reopened first (it was, from onResume, before the new kiosk arrived).
        gate.onStart()
        assertEquals(Step.WAIT, gate.onResume())
        assertEquals(Step.APPLY, gate.onDelivery(KioskDelivery.APPLY))
    }

    @Test fun `first start, delivery before resume - applied once, resume does nothing`() {
        gate.onStart()
        assertEquals(Step.APPLY, gate.onDelivery(KioskDelivery.APPLY))
        assertEquals(Step.NONE, gate.onResume())
    }

    @Test fun `first start, delivery after resume - applied once`() {
        gate.onStart()
        assertEquals(Step.WAIT, gate.onResume())
        assertEquals(Step.APPLY, gate.onDelivery(KioskDelivery.APPLY))
    }

    @Test fun `resumed without being stopped (something translucent on top went away) - handled at once, as before`() {
        gate.onStart()
        gate.onResume()
        gate.onDelivery(KioskDelivery.APPLY)
        assertEquals(Step.RETURN, gate.onResume())
    }

    @Test fun `later deliveries while in front are applied as they come`() {
        gate.onStart()
        gate.onResume()
        gate.onDelivery(KioskDelivery.APPLY)
        assertEquals(Step.APPLY, gate.onDelivery(KioskDelivery.APPLY))
        assertEquals(Step.IDLE, gate.onDelivery(KioskDelivery.IDLE))
    }

    @Test fun `no kiosk any more - idle, whatever the order`() {
        gate.onStart()
        assertEquals(Step.WAIT, gate.onResume())
        assertEquals(Step.IDLE, gate.onDelivery(KioskDelivery.IDLE))
        gate.onStart()
        assertEquals(Step.IDLE, gate.onDelivery(KioskDelivery.IDLE))
        assertEquals(Step.NONE, gate.onResume())
    }
}
