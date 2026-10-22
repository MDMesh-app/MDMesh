package com.mdmesh.policy.location

import com.mdmesh.policy.PolicyOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class KeepLocationEnabledPolicyTest {
    @Test fun `enable location before locking and release without switching it off`() {
        val calls = mutableListOf<String>()
        val policy = KeepLocationEnabledPolicy({ calls.add("on") }, { calls.add("lock=$it") })
        assertEquals(PolicyOutcome.Applied, policy.setEnabled(true))
        assertEquals(listOf("on", "lock=true"), calls)
        calls.clear()
        assertEquals(PolicyOutcome.Applied, policy.release())
        assertEquals(listOf("lock=false"), calls)
        calls.clear()
        policy.setEnabled(false)
        assertEquals(listOf("lock=false"), calls)
    }

    @Test fun `support is checked again when ownership changes`() {
        var owner = true
        val policy = KeepLocationEnabledPolicy({}, {}, { owner })
        assertTrue(policy.isSupported())
        owner = false
        assertFalse(policy.isSupported())
    }

    @Test fun `failed enable does not lock location off`() {
        var locked = false
        val policy = KeepLocationEnabledPolicy({ error("denied") }, { locked = it })
        assertTrue(policy.setEnabled(true) is PolicyOutcome.Failed)
        assertFalse(locked)
    }

    @Test fun `failed restriction is reported for retry`() {
        val policy = KeepLocationEnabledPolicy({}, { error("denied") })
        assertTrue(policy.setEnabled(true) is PolicyOutcome.Failed)
        assertTrue(policy.release() is PolicyOutcome.Failed)
    }
}
