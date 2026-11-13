package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.ResolvedToggles
import com.mdmesh.policy.dns.DnsPolicy
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `policy.apply` — applies a single policy to a value. Payload (per
 * `proto/registry.md`): `{ "policy": "wifi", "value": false }` for a boolean
 * toggle, or `{ "policy": "dns", "value": "dns.google" }` for the string-valued
 * DNS policy.
 *
 * Toggle routing is fully data-driven: the policy key is looked up in [toggles] (the
 * device's supported strategies in the capability-abstraction layer, read per command,
 * never kept: Device-Owner status flips to true partway through QR provisioning, #76).
 * There is no per-policy `when` — adding a toggle policy means registering its strategy,
 * not editing here. Unknown / unsupported keys degrade to `unsupported`, mirroring the
 * open-registry contract; a key whose support check threw fails the command instead.
 *
 * DNS is the one non-boolean policy (a host, or null to clear), so it is handled
 * explicitly before the toggle lookup. Its strategy is resolved per command through
 * [dnsPolicy] for the same Device-Owner reason: never inject the resolved instance.
 */
class PolicyApplyHandler(
    private val toggles: () -> ResolvedToggles,
    private val dnsPolicy: () -> DnsPolicy? = { null },
) : CommandHandler {

    override val type: String = "policy.apply"

    @Serializable
    private data class Payload(
        val policy: String,
        @SerialName("value") val value: JsonElement? = null,
    )

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload
            ?: return CommandResults.failed(command, "policy.apply requires a payload")

        val parsed = runCatching {
            ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), payload)
        }.getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }

        if (parsed.policy == DnsPolicy.CAPABILITY_KEY) {
            val policy = dnsPolicy()
                ?: return CommandResults.unsupported(command, "dns policy not supported on this device")

            val host = when (val value = parsed.value) {
                // A JSON null (or `false`, below) clears the host / falls back to opportunistic mode.
                null, JsonNull -> null
                is JsonPrimitive -> when {
                    value.isString -> value.content
                    value.booleanOrNull == false -> null
                    else -> return CommandResults.failed(command, "dns value must be a hostname string")
                }
                else -> return CommandResults.failed(command, "dns value must be a hostname string")
            }

            return when (val outcome = policy.setDnsHost(host)) {
                PolicyOutcome.Applied -> CommandResults.done(command)
                PolicyOutcome.Unsupported -> CommandResults.unsupported(command)
                is PolicyOutcome.Failed -> CommandResults.failed(command, outcome.reason)
                is PolicyOutcome.Deferred -> CommandResults.done(command, ConfigOutcome.deferred(outcome.reason))
            }
        }

        val resolved = toggles()
        // Fail closed: a check that threw fails the command (retryable), it does not make the key unsupported.
        resolved.errors[parsed.policy]?.let { return CommandResults.failed(command, it.reason) }
        val toggle = resolved[parsed.policy]
            ?: return CommandResults.unsupported(command, "policy not supported: ${parsed.policy}")

        val enabled = when (val value = parsed.value) {
            is JsonPrimitive -> value.booleanOrNull
            else -> null
        } ?: return CommandResults.failed(command, "boolean value required for toggle policy ${parsed.policy}")

        return when (val outcome = toggle.setEnabled(enabled)) {
            PolicyOutcome.Applied -> CommandResults.done(command)
            PolicyOutcome.Unsupported -> CommandResults.unsupported(command)
            is PolicyOutcome.Failed -> CommandResults.failed(command, outcome.reason)
            // Not a failure, as in config.apply: skipped on purpose for now (e.g. Wi-Fi radio Off on the only network).
            is PolicyOutcome.Deferred -> CommandResults.done(command, ConfigOutcome.deferred(outcome.reason))
        }
    }
}
