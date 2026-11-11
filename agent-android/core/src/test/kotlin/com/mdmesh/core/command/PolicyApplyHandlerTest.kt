package com.mdmesh.core.command

import com.mdmesh.core.command.handlers.PolicyApplyHandler
import com.mdmesh.core.testing.fixedToggles
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
        val result = PolicyApplyHandler(fixedToggles("wifi" to wifi)).handle(command(payload("wifi", false)))

        assertEquals(CommandStatus.DONE, result.status)
        assertEquals(false, wifi.lastEnabled)
    }

    @Test
    fun `reports unsupported for a policy with no registered strategy`() = runTest {
        val result = PolicyApplyHandler(fixedToggles()).handle(command(payload("camera", true)))
        assertEquals(CommandStatus.UNSUPPORTED, result.status)
    }

    @Test
    fun `reports failed when the payload is missing`() = runTest {
        val result = PolicyApplyHandler(fixedToggles()).handle(command(payload = null))
        assertEquals(CommandStatus.FAILED, result.status)
    }

    @Test
    fun `surfaces a strategy failure as failed`() = runTest {
        val wifi = FakeToggle("wifi", PolicyOutcome.Failed("dpm blew up"))
        val result = PolicyApplyHandler(fixedToggles("wifi" to wifi)).handle(command(payload("wifi", true)))
        assertEquals(CommandStatus.FAILED, result.status)
    }

    @Test
    fun `a deferred policy is done with the reason, not failed (as in config apply)`() = runTest {
        val radio = FakeToggle("wifiRadio", PolicyOutcome.Deferred("only network"))
        val result = PolicyApplyHandler(fixedToggles("wifiRadio" to radio)).handle(command(payload("wifiRadio", false)))
        assertEquals(CommandStatus.DONE, result.status)
        assertEquals("deferred: only network", result.detail)
    }

    private class FakeDnsPolicy(
        private val outcome: PolicyOutcome,
    ) : com.mdmesh.policy.dns.DnsPolicy {
        override val capabilityKey: String = "dns"
        var lastHost: String? = "unsets"
        override fun isSupported(): Boolean = true
        override fun setDnsHost(dnsHost: String?): PolicyOutcome {
            lastHost = dnsHost
            return outcome
        }
    }

    @Test
    fun `applies dns policy with string host value`() = runTest {
        val dns = FakeDnsPolicy(PolicyOutcome.Applied)
        val handler = PolicyApplyHandler(fixedToggles(), dnsPolicy = dns)

        val dnsPayload = buildJsonObject {
            put("policy", "dns")
            put("value", "1.1.1.1")
        }
        val result = handler.handle(command(dnsPayload))

        assertEquals(CommandStatus.DONE, result.status)
        assertEquals("1.1.1.1", dns.lastHost)
    }
}
