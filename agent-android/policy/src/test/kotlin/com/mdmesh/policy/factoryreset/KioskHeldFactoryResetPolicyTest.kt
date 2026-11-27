package com.mdmesh.policy.factoryreset

import com.mdmesh.policy.PolicyOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class KioskHeldFactoryResetPolicyTest {

    private class Recording : FactoryResetPolicy {
        val calls = mutableListOf<Boolean>()
        override val capabilityKey = FactoryResetPolicy.CAPABILITY_KEY
        override fun isSupported() = true
        override fun setEnabled(enabled: Boolean): PolicyOutcome {
            calls += enabled
            return PolicyOutcome.Applied
        }
    }

    @Test
    fun `applies both ways outside kiosk`() {
        val inner = Recording()
        val policy = KioskHeldFactoryResetPolicy(inner) { false }
        assertEquals(PolicyOutcome.Applied, policy.setEnabled(true))
        assertEquals(PolicyOutcome.Applied, policy.setEnabled(false))
        assertEquals(listOf(true, false), inner.calls)
    }

    @Test
    fun `defers Allow and still applies Block while kiosk holds the block`() {
        val inner = Recording()
        val policy = KioskHeldFactoryResetPolicy(inner) { true }
        assertEquals(PolicyOutcome.Deferred(KioskHeldFactoryResetPolicy.IN_KIOSK), policy.setEnabled(true))
        assertEquals(PolicyOutcome.Applied, policy.setEnabled(false))
        assertEquals(listOf(false), inner.calls)
    }

    @Test
    fun `an Allow held by kiosk never reaches the wipe hold or the device`() {
        // The factory's order: kiosk outermost.
        val inner = Recording()
        val policy = KioskHeldFactoryResetPolicy(WipeHeldFactoryResetPolicy(inner, FactoryResetWipeHold())) { true }
        policy.setEnabled(true)
        assertEquals(emptyList<Boolean>(), inner.calls)
    }

    @Test
    fun `while held, every setting is remembered for when kiosk ends`() {
        val asked = mutableListOf<Boolean>()
        val hold = object : FactoryResetKioskHold {
            override fun held() = true
            override fun remember(blocked: Boolean): Boolean { asked += blocked; return true }
        }
        val policy = KioskHeldFactoryResetPolicy(Recording(), hold)
        policy.setEnabled(false)
        policy.setEnabled(true)
        assertEquals(listOf(true, false), asked)
    }

    @Test
    fun `a setting the kiosk can't keep for its exit is reported failed, never applied`() {
        val hold = object : FactoryResetKioskHold {
            override fun held() = true
            override fun remember(blocked: Boolean) = false
        }
        val inner = Recording()
        val policy = KioskHeldFactoryResetPolicy(inner, hold)
        assertEquals(PolicyOutcome.Failed(KioskHeldFactoryResetPolicy.NOT_KEPT), policy.setEnabled(false))
        assertEquals(PolicyOutcome.Failed(KioskHeldFactoryResetPolicy.NOT_KEPT), policy.setEnabled(true))
        assertEquals("the block itself still applies", listOf(false), inner.calls)
    }
}
