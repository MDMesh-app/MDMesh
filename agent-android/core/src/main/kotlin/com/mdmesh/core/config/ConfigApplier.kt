package com.mdmesh.core.config

import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.ReleasablePolicy
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.wifi.WifiRadioPolicy
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigApplyResult
import com.mdmesh.proto.ConfigOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Converges the device to a desired-state document. Each present section is applied through the code that
 * already serves the imperative commands (toggle strategies, [KioskApplier], location mode), so `config.apply`
 * adds no new device behavior — only orchestration and reporting.
 *
 * Idempotent: applying the same document twice is a no-op at the OS level. The document is persisted (and its
 * revision reported to the server) only when no section failed; `unsupported` is final and `deferred` (1.4) is
 * deliberate, so neither blocks.
 *
 * Serialized: a boot re-apply ([reapplyPersisted]) and a freshly delivered `config.apply` never interleave —
 * otherwise an older persisted document could finish last and overwrite the newer one.
 */
class ConfigApplier(
    private val toggles: Map<String, TogglePolicy>,
    private val kiosk: KioskApplier,
    private val setLocationMode: (String) -> Unit,
    private val store: ConfigStateStore,
) {
    private val mutex = Mutex()

    suspend fun apply(doc: ConfigApplyPayload): ConfigApplyResult = mutex.withLock {
        // A changed configuration ends a Wi-Fi radio self-heal hold; a re-send of the same one does not (nor does the
        // boot re-apply, which does not come through here).
        if (doc.revision != store.deliveredRevision) wifiRadio()?.endHold()
        store.deliveredRevision = doc.revision
        applyLocked(doc)
    }

    /** @param persist false for the boot re-apply: the document is already stored (or stale until re-sent). */
    private suspend fun applyLocked(doc: ConfigApplyPayload, persist: Boolean = true): ConfigApplyResult {
        val outcomes = linkedMapOf<String, String>()
        for ((key, enabled) in doc.policies) {
            outcomes["policies.$key"] = toOutcome(toggles[key]?.setEnabled(enabled))
        }
        releaseUnmanaged(doc, outcomes)
        applyKiosk(doc)?.let { outcomes["kiosk"] = it }
        doc.location?.let { loc ->
            outcomes["location"] = runCatching { setLocationMode(loc.mode); ConfigOutcome.APPLIED }
                .getOrElse { ConfigOutcome.failed(it.message ?: "location mode") }
        }
        val result = ConfigApplyResult(doc.revision, outcomes)
        if (persist && succeeded(result)) store.save(doc)
        return result
    }

    /**
     * A key the previously applied document managed and this one does not: lift what it left behind (once; the new
     * document is what gets persisted). Reported only when the release failed, so it is retried.
     */
    private fun releaseUnmanaged(doc: ConfigApplyPayload, outcomes: MutableMap<String, String>) {
        val previous = store.load()?.policies?.keys.orEmpty()
        for (key in previous - doc.policies.keys) {
            val releasable = toggles[key] as? ReleasablePolicy ?: continue
            val o = toOutcome(releasable.release())
            if (ConfigOutcome.isFailed(o)) outcomes["policies.$key"] = o
        }
    }

    private fun toOutcome(o: PolicyOutcome?): String = when (o) {
        null, PolicyOutcome.Unsupported -> ConfigOutcome.UNSUPPORTED
        PolicyOutcome.Applied -> ConfigOutcome.APPLIED
        is PolicyOutcome.Failed -> ConfigOutcome.failed(o.reason)
        is PolicyOutcome.Deferred -> ConfigOutcome.deferred(o.reason)
    }

    /** @return the kiosk outcome, or null when nothing was asserted or exited (the key is then omitted). */
    private suspend fun applyKiosk(doc: ConfigApplyPayload): String? {
        // Absent kiosk = "configuration does not assert kiosk". Exit only when the LAST APPLIED CONFIG asserted
        // it (the admin turned it off). Kiosk entered by an ad-hoc kiosk.enter is never lifted here — otherwise
        // the first apply after upgrading would drop every manually-kiosked device.
        val previousConfigHadKiosk = store.load()?.kiosk != null
        val desiredKiosk = doc.kiosk
        val r = when {
            desiredKiosk != null -> kiosk.enter(desiredKiosk)
            previousConfigHadKiosk && kiosk.isPersisted() -> kiosk.exit()
            else -> return null
        }
        return when (r) {
            KioskResult.Ok -> ConfigOutcome.APPLIED
            KioskResult.Unsupported -> ConfigOutcome.UNSUPPORTED
            is KioskResult.Failed -> ConfigOutcome.failed(r.reason)
        }
    }

    /**
     * Re-run the last fully-applied document (after boot / self-update). Null when nothing is persisted. Not saved
     * again: a stale document ([selfHealWifiRadio]) stays stale until the server re-sends it.
     */
    suspend fun reapplyPersisted(): ConfigApplyResult? = mutex.withLock {
        store.load()?.let { applyLocked(it, persist = false) }
    }

    /**
     * Heartbeat: undo a `wifiRadio` Off after which the device could not reach the server ([WifiRadioPolicy.selfHeal]).
     * When Wi-Fi was turned back on, the store reports a stale revision, so the server re-sends config.apply, whose
     * result then shows the Off as deferred. Serialized with [apply] and [reapplyPersisted].
     */
    suspend fun selfHealWifiRadio(lastCheckInOkMs: Long?): Boolean = mutex.withLock {
        val radio = wifiRadio() ?: return@withLock false
        if (!radio.selfHeal(lastCheckInOkMs)) return@withLock false
        store.markStale()
        true
    }

    private fun wifiRadio(): WifiRadioPolicy? = toggles[WifiRadioPolicy.CAPABILITY_KEY] as? WifiRadioPolicy

    companion object {
        fun succeeded(r: ConfigApplyResult): Boolean = r.outcomes.values.none(ConfigOutcome::isFailed)
    }
}
