package com.mdmesh.policy.factoryreset

import com.mdmesh.policy.PolicyOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class WipeHeldFactoryResetPolicyTest {

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
    fun `applies both ways while no wipe is held`() {
        val inner = Recording()
        val policy = WipeHeldFactoryResetPolicy(inner, FactoryResetWipeHold())

        assertEquals(PolicyOutcome.Applied, policy.setEnabled(false))
        assertEquals(PolicyOutcome.Applied, policy.setEnabled(true))
        assertEquals(listOf(false, true), inner.calls)
    }

    @Test
    fun `defers Block and still applies Allow while a wipe is held`() {
        val inner = Recording()
        val hold = FactoryResetWipeHold().apply { hold() }
        val policy = WipeHeldFactoryResetPolicy(inner, hold)

        assertEquals(PolicyOutcome.Deferred(WipeHeldFactoryResetPolicy.WIPING), policy.setEnabled(false))
        assertEquals(PolicyOutcome.Applied, policy.setEnabled(true))
        assertEquals(listOf(true), inner.calls)
    }

    @Test
    fun `applies Block again once the hold is released`() {
        val inner = Recording()
        val hold = FactoryResetWipeHold().apply { hold() }
        val policy = WipeHeldFactoryResetPolicy(inner, hold)

        hold.release()

        assertEquals(PolicyOutcome.Applied, policy.setEnabled(false))
        assertEquals(listOf(false), inner.calls)
    }
}
