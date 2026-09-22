package com.mdmesh.core.config

import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigApplyResult
import com.mdmesh.proto.ConfigOutcome

/**
 * Converges the device to a desired-state document. Each present section is applied through the code that
 * already serves the imperative commands (toggle strategies, [KioskApplier], location mode), so `config.apply`
 * adds no new device behavior — only orchestration and reporting.
 *
 * Idempotent: applying the same document twice is a no-op at the OS level. The document is persisted (and its
 * revision reported to the server) only when no section failed; `unsupported` is final and does not block.
 */
class ConfigApplier(
    private val toggles: Map<String, TogglePolicy>,
    private val kiosk: KioskApplier,
    private val setLocationMode: (String) -> Unit,
    private val store: ConfigStateStore,
) {
    suspend fun apply(doc: ConfigApplyPayload): ConfigApplyResult {
        val outcomes = linkedMapOf<String, String>()
        for ((key, enabled) in doc.policies) {
            outcomes["policies.$key"] = when (val o = toggles[key]?.setEnabled(enabled)) {
                null, PolicyOutcome.Unsupported -> ConfigOutcome.UNSUPPORTED
                PolicyOutcome.Applied -> ConfigOutcome.APPLIED
                is PolicyOutcome.Failed -> ConfigOutcome.failed(o.reason)
            }
        }
        outcomes["kiosk"] = applyKiosk(doc)
        doc.location?.let { loc ->
            outcomes["location"] = runCatching { setLocationMode(loc.mode); ConfigOutcome.APPLIED }
                .getOrElse { ConfigOutcome.failed(it.message ?: "location mode") }
        }
        val result = ConfigApplyResult(doc.revision, outcomes)
        if (succeeded(result)) store.save(doc)
        return result
    }

    private suspend fun applyKiosk(doc: ConfigApplyPayload): String {
        // Absent kiosk = "configuration does not assert kiosk". Exit only when the LAST APPLIED CONFIG asserted
        // it (the admin turned it off). Kiosk entered by an ad-hoc kiosk.enter is never lifted here — otherwise
        // the first apply after upgrading would drop every manually-kiosked device.
        val previousConfigHadKiosk = store.load()?.kiosk != null
        val desiredKiosk = doc.kiosk
        val r = when {
            desiredKiosk != null -> kiosk.enter(desiredKiosk)
            previousConfigHadKiosk && kiosk.isPersisted() -> kiosk.exit()
            else -> KioskResult.Ok
        }
        return when (r) {
            KioskResult.Ok -> ConfigOutcome.APPLIED
            KioskResult.Unsupported -> ConfigOutcome.UNSUPPORTED
            is KioskResult.Failed -> ConfigOutcome.failed(r.reason)
        }
    }

    /** Re-run the last fully-applied document (after boot / self-update). Null when nothing is persisted. */
    suspend fun reapplyPersisted(): ConfigApplyResult? = store.load()?.let { apply(it) }

    companion object {
        fun succeeded(r: ConfigApplyResult): Boolean = r.outcomes.values.none(ConfigOutcome::isFailed)
    }
}
