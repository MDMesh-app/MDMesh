package com.mdmesh.core.command

import com.mdmesh.core.command.handlers.PolicyApplyHandler
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyApplyHandlerTest {

    private class FakeToggle(
        override val capabilityKey: String,
        private val outcome: PolicyOutcome,
    ) : TogglePolicy {
        var lastEnabled: Boolean? = null
        override fun isSupported(): Boolean = true
        override fun setEnabled(enabled: Boolean): PolicyOutcome {
            lastEnabled = enabled
            return outcome
        }
    }

    private fun command(payload: JsonObject?) = CommandEnvelope(
        commandId = "c1",
        issuedAt = "2026-01-01T00:00:00Z",
        type = "policy.apply",
        payload = payload,
    )

    private fun payload(policy: String, value: Boolean) = buildJsonObject {
        put("policy", policy)
        put("value", value)
    }

    @Test
    fun `applies a known toggle policy and reports done`() = runTest {
        val wifi = FakeToggle("wifi", PolicyOutcome.Applied)
        val result = PolicyApplyHandler(mapOf("wifi" to wifi)).handle(command(payload("wifi", false)))

        assertEquals(CommandStatus.DONE, result.status)
        assertEquals(false, wifi.lastEnabled)
    }

    @Test
    fun `reports unsupported for a policy with no registered strategy`() = runTest {
        val result = PolicyApplyHandler(emptyMap()).handle(command(payload("camera", true)))
        assertEquals(CommandStatus.UNSUPPORTED, result.status)
    }

    @Test
    fun `reports failed when the payload is missing`() = runTest {
        val result = PolicyApplyHandler(emptyMap()).handle(command(payload = null))
        assertEquals(CommandStatus.FAILED, result.status)
    }

    @Test
    fun `surfaces a strategy failure as failed`() = runTest {
        val wifi = FakeToggle("wifi", PolicyOutcome.Failed("dpm blew up"))
        val result = PolicyApplyHandler(mapOf("wifi" to wifi)).handle(command(payload("wifi", true)))
        assertEquals(CommandStatus.FAILED, result.status)
    }
}
