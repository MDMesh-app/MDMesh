package com.mdmesh.core.command.handlers

import com.mdmesh.core.config.AppRestrictions
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.UserAppPolicy
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `policy.apply` — applies a single toggle policy to a value. Payload (per
 * `proto/registry.md`): `{ "policy": "wifi", "value": false }`.
 *
 * Routing is fully data-driven: the policy key is looked up in [toggles] (built from the
 * device's supported strategies in the capability-abstraction layer). There is no
 * per-policy `when` — adding a policy means registering its strategy, not editing here.
 * Unknown / unsupported keys degrade to `unsupported`, mirroring the open-registry contract.
 */
class PolicyApplyHandler(
    private val toggles: Map<String, TogglePolicy>,
    private val appRestrictions: AppRestrictions? = null,
) : CommandHandler {

    override val type: String = "policy.apply"

    @Serializable
    private data class Payload(
        val policy: String,
        @SerialName("value") val enabled: Boolean,
    )

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload
            ?: return CommandResults.failed(command, "policy.apply requires a payload")

        val parsed = runCatching {
            ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), payload)
        }.getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }

        if (parsed.policy in UserAppPolicy.KEYS && appRestrictions != null) {
            val outcome = appRestrictions.apply(mapOf(parsed.policy to parsed.enabled), null).getValue(parsed.policy)
            return when (outcome) {
                ConfigOutcome.APPLIED -> CommandResults.done(command)
                ConfigOutcome.UNSUPPORTED -> CommandResults.unsupported(command)
                else -> CommandResults.failed(command, outcome)
            }
        }
        val toggle = toggles[parsed.policy]
            ?: return CommandResults.unsupported(command, "policy not supported: ${parsed.policy}")

        return when (val outcome = toggle.setEnabled(parsed.enabled)) {
            PolicyOutcome.Applied -> CommandResults.done(command)
            PolicyOutcome.Unsupported -> CommandResults.unsupported(command)
            is PolicyOutcome.Failed -> CommandResults.failed(command, outcome.reason)
        }
    }
}
