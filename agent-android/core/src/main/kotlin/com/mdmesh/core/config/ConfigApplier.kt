package com.mdmesh.core.config

import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.ReleasablePolicy
import com.mdmesh.policy.ResolvedToggles
import com.mdmesh.policy.safeboot.SafeBootPolicy
import com.mdmesh.policy.wifi.WifiRadioPolicy
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigApplyResult
import com.mdmesh.proto.ConfigOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Converges the device to a desired-state document. Each present section is applied through the code that
 * already serves the imperative commands (toggle strategies, [KioskApplier], location mode, connectivity mode), so
 * `config.apply` adds no new device behavior — only orchestration and reporting.
 *
 * Idempotent: applying the same document twice is a no-op at the OS level. The document is persisted (and its
 * revision reported to the server) only when no section failed; `unsupported` is final and `deferred` (1.4) is
 * deliberate, so neither blocks.
 *
 * Serialized: a boot re-apply ([reapplyPersisted]) and a freshly delivered `config.apply` never interleave —
 * otherwise an older persisted document could finish last and overwrite the newer one.
 *
 * @param toggles the toggle policies supported right now (`CapabilityRegistry.togglePolicies`), read once per
 *   operation and never kept: Device-Owner status flips to true partway through QR provisioning, after this
 *   singleton may have been built (#76). A key whose check threw ([ResolvedToggles.errors]) is reported failed,
 *   never unsupported, so the document is not saved and the server re-sends it; it is never applied or released.
 * @param setPowerMode sets the live-connection mode for the `connectivity` section (1.5), as `device.powerMode` does.
 */
class ConfigApplier(
    private val toggles: () -> ResolvedToggles,
    private val kiosk: KioskApplier,
    private val setLocationMode: (String) -> Unit,
    private val store: ConfigStateStore,
    private val setPowerMode: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun apply(doc: ConfigApplyPayload): ConfigApplyResult = mutex.withLock {
        // What came before: the last saved document, and the last delivered one if a failed section kept it unsaved.
        val previous = listOfNotNull(store.load(), store.delivered)
        val policies = toggles()
        endWifiRadioHold(doc.revision, policies)
        store.delivered = doc
        val result = applyLocked(doc, policies, previous, store.unreleased)
        // A release or kiosk exit that failed is retried with the next document, whatever that one says.
        store.unreleased = result.outcomes.filter { (k, o) -> ConfigOutcome.isFailed(o) && !manages(doc, k) }.keys
        result
    }

    /**
     * A changed configuration ends a Wi-Fi radio self-heal hold; a re-send of the same one does not (nor does the boot
     * re-apply, which does not come through here). When the `wifiRadio` check throws and no instance was kept, the
     * hold cannot be ended now: [ConfigStateStore.holdEndedFor] stays as it was, so the re-send of this revision
     * ends it.
     */
    private fun endWifiRadioHold(revision: String, policies: ResolvedToggles) {
        // A store from before holdEndedFor existed: the last delivered revision had ended the hold.
        val endedFor = store.holdEndedFor ?: store.delivered?.revision
        if (revision == endedFor) return
        val errored = policies.errors[WifiRadioPolicy.CAPABILITY_KEY]
        // endHold only clears saved state, so it also runs on a kept instance whose check threw just now.
        val radio = wifiRadio(policies) ?: errored?.kept as? WifiRadioPolicy
        store.holdEndedFor = when {
            radio != null -> revision.also { radio.endHold() }
            errored != null -> endedFor ?: NO_REVISION // not ended yet: kept explicit, so the fallback above stops
            else -> revision // no wifiRadio on this device right now: no hold to end
        }
    }

    private fun manages(doc: ConfigApplyPayload, outcomeKey: String): Boolean = when {
        outcomeKey == KIOSK -> doc.kiosk != null
        outcomeKey.startsWith(POLICIES) -> outcomeKey.removePrefix(POLICIES) in doc.policies
        else -> true
    }

    /**
     * @param toggles the supported toggle policies, read once for this document.
     * @param previous the documents whose keys this one may stop managing.
     * @param unreleased outcome keys whose release (or kiosk exit) failed before: released again.
     * @param persist false for the boot re-apply: the document is already stored (or stale until re-sent).
     */
    private suspend fun applyLocked(
        doc: ConfigApplyPayload,
        toggles: ResolvedToggles,
        previous: List<ConfigApplyPayload>,
        unreleased: Set<String> = emptySet(),
        persist: Boolean = true,
    ): ConfigApplyResult {
        val outcomes = linkedMapOf<String, String>()
        for ((key, enabled) in doc.policies) {
            // Fail closed: a check that threw is a failure (retried), not an unsupported key (final).
            val errored = toggles.errors[key]
            outcomes["$POLICIES$key"] = if (errored != null) {
                ConfigOutcome.failed(errored.reason)
            } else {
                toOutcome(toggles[key]?.setEnabled(enabled))
            }
        }
        val previousKeys = previous.flatMap { it.policies.keys } +
            unreleased.filter { it.startsWith(POLICIES) }.map { it.removePrefix(POLICIES) }
        releaseUnmanaged(doc, toggles, previousKeys.toSet(), outcomes)
        val previousKiosk = previous.any { it.kiosk != null } || KIOSK in unreleased
        applyKiosk(doc, previousKiosk)?.let { outcomes[KIOSK] = it }
        // Last: the policies and the kiosk are now what this document says, so Safe Mode can follow both. The
        // setting is the delivered document's (ConfiguredSafeModeSetting): this one on a normal apply (recorded as
        // delivered above), and on the boot re-apply the newer one the server sent, if any. Only a failed block is
        // reported, on policies.safeBoot, so the document is re-applied.
        (kiosk.syncSafeMode() as? KioskResult.Failed)?.let {
            outcomes["$POLICIES${SafeBootPolicy.CAPABILITY_KEY}"] = ConfigOutcome.failed(it.reason)
        }
        doc.location?.let { loc ->
            outcomes["location"] = runCatching { setLocationMode(loc.mode); ConfigOutcome.APPLIED }
                .getOrElse { ConfigOutcome.failed(it.message ?: "location mode") }
        }
        // Absent = not managed: the device keeps its mode, including one set by a device.powerMode command.
        doc.connectivity?.let { c ->
            outcomes[CONNECTIVITY] = runCatching { setPowerMode(c.mode); ConfigOutcome.APPLIED }
                .getOrElse { ConfigOutcome.failed(it.message ?: "connectivity mode") }
        }
        val result = ConfigApplyResult(doc.revision, outcomes)
        if (persist && succeeded(result)) store.save(doc)
        return result
    }

    /**
     * A key a previous document (last saved or last delivered) managed, or whose release failed before, and this one
     * does not: lift what it left behind. Reported only when the release failed, which keeps it in
     * [ConfigStateStore.unreleased], so the next document retries it.
     */
    private fun releaseUnmanaged(
        doc: ConfigApplyPayload,
        toggles: ResolvedToggles,
        previousKeys: Set<String>,
        outcomes: MutableMap<String, String>,
    ) {
        for (key in previousKeys - doc.policies.keys) {
            releaseOutcome(toggles, key)?.takeIf(ConfigOutcome::isFailed)?.let { outcomes["$POLICIES$key"] = it }
        }
    }

    /** The release outcome for [key], or null when it has nothing to release. */
    private fun releaseOutcome(toggles: ResolvedToggles, key: String): String? {
        val errored = toggles.errors[key]
        // Never released while its check throws. Failed unless it is known not to need a release, which keeps it in
        // ConfigStateStore.unreleased (and the document unsaved), so it is retried.
        if (errored != null) {
            val mayNeedRelease = errored.kept == null || errored.kept is ReleasablePolicy
            return ConfigOutcome.failed(errored.reason).takeIf { mayNeedRelease }
        }
        return (toggles[key] as? ReleasablePolicy)?.let { toOutcome(it.release()) }
    }

    /** @return the kiosk outcome, or null when nothing was asserted or exited (the key is then omitted). */
    private suspend fun applyKiosk(doc: ConfigApplyPayload, previousConfigHadKiosk: Boolean): String? {
        // Absent kiosk = "configuration does not assert kiosk". Exit only when a PREVIOUS CONFIG (last saved or last
        // delivered, or one whose exit failed) asserted it (the admin turned it off). Kiosk entered by an ad-hoc
        // kiosk.enter is never lifted here — otherwise the first apply after upgrading would drop every
        // manually-kiosked device.
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
        store.load()?.let { applyLocked(it, toggles(), listOf(it), persist = false) }
    }

    /**
     * Heartbeat: undo a `wifiRadio` Off after which the device could not reach the server ([WifiRadioPolicy.selfHeal]).
     * When Wi-Fi was turned back on, the store reports a stale revision, so the server re-sends config.apply, whose
     * result then shows the Off as deferred. Serialized with [apply] and [reapplyPersisted].
     *
     * @param lastCheckInOkMs when a check-in last reached the server (wall clock), null when never.
     */
    suspend fun selfHealWifiRadio(lastCheckInOkMs: () -> Long?): Boolean = mutex.withLock {
        // Null also when its check threw: this heartbeat is skipped and the next one checks again.
        val radio = wifiRadio(toggles()) ?: return@withLock false
        // Read under the lock, not before waiting for it.
        if (!radio.selfHeal(lastCheckInOkMs())) return@withLock false
        store.markStale()
        true
    }

    private fun wifiRadio(toggles: ResolvedToggles): WifiRadioPolicy? =
        toggles[WifiRadioPolicy.CAPABILITY_KEY] as? WifiRadioPolicy

    companion object {
        private const val POLICIES = "policies."
        private const val KIOSK = "kiosk"
        private const val CONNECTIVITY = "connectivity"

        /** Never a real revision (config.apply requires a non-blank one). */
        private const val NO_REVISION = ""

        fun succeeded(r: ConfigApplyResult): Boolean = r.outcomes.values.none(ConfigOutcome::isFailed)
    }
}

private fun toOutcome(o: PolicyOutcome?): String = when (o) {
    null, PolicyOutcome.Unsupported -> ConfigOutcome.UNSUPPORTED
    PolicyOutcome.Applied -> ConfigOutcome.APPLIED
    is PolicyOutcome.Failed -> ConfigOutcome.failed(o.reason)
    is PolicyOutcome.Deferred -> ConfigOutcome.deferred(o.reason)
}
