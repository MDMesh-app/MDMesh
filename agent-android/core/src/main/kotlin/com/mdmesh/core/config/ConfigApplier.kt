package com.mdmesh.core.config

import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.configuredKiosk
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.ReleasablePolicy
import com.mdmesh.policy.ResolvedToggles
import com.mdmesh.policy.AppRuntimePermissions
import com.mdmesh.policy.PermissionGrantLock
import com.mdmesh.policy.AppPermissionOutcome
import com.mdmesh.policy.UnsupportedAppRuntimePermissions
import com.mdmesh.policy.factoryreset.FactoryResetPolicy
import com.mdmesh.policy.safeboot.SafeBootPolicy
import com.mdmesh.policy.wifi.WifiRadioPolicy
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigApplyResult
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.CancellationException
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
 *   Only called with a mode this agent knows; another one is reported unsupported.
 */
// Coordinator for independently releasable desired-state sections; each collaborator is one section's device side.
@Suppress("TooManyFunctions", "LongParameterList")
class ConfigApplier(
    private val toggles: () -> ResolvedToggles,
    private val kiosk: KioskApplier,
    private val setLocationMode: (String) -> Unit,
    private val store: ConfigStateStore,
    private val setPowerMode: (String) -> Unit,
    private val appPermissions: AppRuntimePermissions = UnsupportedAppRuntimePermissions,
    /** Shared with the kiosk guard, the other writer of grant states ([PermissionGrantLock]). */
    private val grantLock: PermissionGrantLock = PermissionGrantLock(),
) {
    private val mutex = Mutex()

    /**
     * Staff can restore the assigned kiosk locally, including offline. Read the current document under the same
     * lock as config.apply so an older button click cannot restore a kiosk a newer configuration disabled.
     * [expected], when given, is the kiosk the person confirmed (its exit, above all): a different one delivered
     * since is refused rather than entered unseen.
     */
    suspend fun returnToConfiguredKiosk(expected: KioskApplyPayload? = null): KioskResult = mutex.withLock {
        val payload = store.configuredKiosk()
            ?: return@withLock KioskResult.Failed("The device configuration does not enable kiosk.")
        if (expected != null && payload != expected) {
            return@withLock KioskResult.Failed("The kiosk settings just changed. Check them and try again.")
        }
        kiosk.enter(payload)
    }

    suspend fun apply(doc: ConfigApplyPayload): ConfigApplyResult = mutex.withLock { applyDeliveredLocked(doc) }

    /**
     * Runs [block] with no apply in progress and none able to start. `device.wipe` lifts Factory reset: Block
     * for the wipe; a boot re-apply must not put it back in between (#222).
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    private suspend fun applyDeliveredLocked(doc: ConfigApplyPayload): ConfigApplyResult {
        // What came before: the last saved document, and the last delivered one if a failed section kept it unsaved.
        val previous = listOfNotNull(store.load(), store.delivered)
        val policies = toggles()
        endWifiRadioHold(doc.revision, policies)
        store.delivered = doc
        val result = applyLocked(doc, policies, previous, store.unreleased)
        // A release or kiosk exit that failed is retried with the next document, whatever that one says.
        store.unreleased = result.outcomes.filter { (k, o) -> ConfigOutcome.isFailed(o) && !manages(doc, k) }.keys
        return result
    }

    /**
     * `app.install` just installed or updated [packageName]: apply the configuration's permission rules for it now.
     * The server queues `config.apply` before the configuration's `app.install`s, so a new app's rules first fail as
     * not installed; without this they would wait for the server's re-send, an hour later, while the app ran under
     * the device-wide auto-grant. An update may also request a configured permission the old version didn't.
     *
     * A delivered document that is not saved yet is applied whole, exactly as the server's re-send would, so it is
     * saved (and reported in sync) when nothing else fails. The saved one gets only this app's rules again; if one
     * fails, the revision is marked stale so the server re-sends the document. Null when no rule names the app.
     */
    suspend fun applyAfterInstall(packageName: String): ConfigApplyResult? = mutex.withLock {
        val doc = store.delivered ?: store.load() ?: return@withLock null
        val rules = doc.appPermissions.orEmpty().filter { it.packageName == packageName }.flatMap { entry ->
            entry.permissions.map { (permission, state) -> AppPermissionRule(packageName, permission, state) }
        }
        if (rules.isEmpty()) return@withLock null
        // Against the saved document, not revision(): a stale mark (a wipe, storage recovery, the Wi-Fi self-heal) must
        // not cut an unsaved newer document down to this app's rules.
        if (doc.revision != store.load()?.revision) return@withLock applyDeliveredLocked(doc)
        val outcomes = rules.associate { it.key to setRule(it) }
        if (outcomes.values.any(ConfigOutcome::isFailed)) store.markStale()
        ConfigApplyResult(doc.revision, outcomes)
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
        val radio = policies.wifiRadio() ?: errored?.kept as? WifiRadioPolicy
        store.holdEndedFor = when {
            radio != null -> revision.also { radio.endHold() }
            errored != null -> endedFor ?: NO_REVISION // not ended yet: kept explicit, so the fallback above stops
            else -> revision // no wifiRadio on this device right now: no hold to end
        }
    }

    private fun manages(doc: ConfigApplyPayload, outcomeKey: String): Boolean = when {
        outcomeKey == KIOSK -> doc.kiosk != null
        outcomeKey.startsWith(POLICIES) -> outcomeKey.removePrefix(POLICIES) in doc.policies
        outcomeKey.startsWith(APP_PERMISSIONS) -> appPermissionKeys(doc).contains(outcomeKey)
        else -> true
    }

    /**
     * @param toggles the supported toggle policies, read once for this document.
     * @param previous the documents whose keys this one may stop managing.
     * @param unreleased outcome keys whose release (or kiosk exit) failed before: released again.
     * @param persist false for the boot re-apply: never saved there, so the server re-sends what isn't saved yet.
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
                section("policy $key") { toOutcome(toggles[key]?.setEnabled(enabled)) }
            }
        }
        val previousKeys = previous.flatMap { it.policies.keys } +
            unreleased.filter { it.startsWith(POLICIES) }.map { it.removePrefix(POLICIES) }
        releaseUnmanaged(doc, toggles, previousKeys.toSet(), outcomes)
        val previousKiosk = previous.any { it.kiosk != null } || KIOSK in unreleased
        section(KIOSK) { applyKiosk(doc, previousKiosk) }?.let { outcomes[KIOSK] = it }
        // The guard, on its own key: a document with a kiosk has just armed it through its entry; one without settles
        // it here, whatever left it (an exit just now, an ad-hoc kiosk, a past failure).
        reconcileKioskGuard(doc, outcomes)
        reportHeldFactoryReset(doc, toggles, outcomes)
        // Last: the policies and the kiosk are now what this document says, so Safe Mode can follow both. The
        // setting is the delivered document's (ConfiguredSafeModeSetting): this one on a normal apply (recorded as
        // delivered above), and on the boot re-apply the newer one the server sent, if any. Only a failed block is
        // reported, on policies.safeBoot, so the document is re-applied.
        section("safe mode") { (kiosk.syncSafeMode() as? KioskResult.Failed)?.let { ConfigOutcome.failed(it.reason) } }
            ?.let { outcomes["$POLICIES${SafeBootPolicy.CAPABILITY_KEY}"] = it }
        doc.location?.let { loc ->
            outcomes["location"] = runCatching { setLocationMode(loc.mode); ConfigOutcome.APPLIED }
                .getOrElse { ConfigOutcome.failed(it.message ?: "location mode") }
        }
        // Absent = not managed: the device keeps its mode, including one set by a device.powerMode command.
        doc.connectivity?.let { c ->
            // A mode from a newer server is not set as Battery-saver: unsupported, like a policy key this agent lacks.
            outcomes[CONNECTIVITY] = if (c.mode !in POWER_MODES) {
                ConfigOutcome.UNSUPPORTED
            } else {
                runCatching { setPowerMode(c.mode); ConfigOutcome.APPLIED }
                    .getOrElse { ConfigOutcome.failed(it.message ?: "connectivity mode") }
            }
        }
        applyAppPermissions(doc, previous, unreleased, outcomes)
        val result = ConfigApplyResult(doc.revision, outcomes)
        if (persist && succeeded(result)) store.save(doc)
        return result
    }

    /** Applies desired app rules; an explicit empty section releases rules in the prior desired document. */
    private fun applyAppPermissions(
        doc: ConfigApplyPayload,
        previous: List<ConfigApplyPayload>,
        unreleased: Set<String>,
        outcomes: MutableMap<String, String>,
    ) {
        val desired = doc.appPermissions ?: return
        val desiredRules = desired.flatMap { entry -> entry.permissions.map { (permission, state) ->
            AppPermissionRule(entry.packageName, permission, state)
        } }
        desiredRules.forEach { rule -> outcomes[rule.key] = section(rule.key) { setRule(rule) } }
        val priorRules = priorAppPermissionRules(previous) +
            unreleased.filter { it.startsWith(APP_PERMISSIONS) && it !in appPermissionKeys(doc) }
            .mapNotNull(::ruleFromKey)
        val desiredKeys = desiredRules.map { it.key }.toSet()
        priorRules.distinctBy { it.key }.filter { it.key !in desiredKeys }.forEach { rule ->
            section(rule.key) { appPermissionOutcome(release(rule)) }
                ?.takeIf(ConfigOutcome::isFailed)
                ?.let { outcomes[rule.key] = it }
        }
        if (outcomes.none { (key, outcome) ->
                key.startsWith(APP_PERMISSIONS) && ConfigOutcome.isFailed(outcome)
            }
        ) {
            store.activeAppPermissions = desired
        }
    }

    private fun priorAppPermissionRules(previous: List<ConfigApplyPayload>): List<AppPermissionRule> =
        (store.activeAppPermissions + previous.flatMap { it.appPermissions.orEmpty() }).flatMap { entry ->
            entry.permissions.keys.map { permission ->
                AppPermissionRule(entry.packageName, permission, AppRuntimePermissions.DEFAULT)
            }
        }

    /** A state this agent doesn't know (a newer server's) is unsupported, never applied as something else. */
    private fun setRule(rule: AppPermissionRule): String =
        if (rule.state != AppRuntimePermissions.GRANT && rule.state != AppRuntimePermissions.DENY) {
            ConfigOutcome.UNSUPPORTED
        } else {
            appPermissionOutcome(write(rule))
        }

    /** A rule's write, under the lock the kiosk guard takes for the same pairs ([PermissionGrantLock]). */
    private fun write(rule: AppPermissionRule): AppPermissionOutcome =
        grantLock.withLock { appPermissions.set(rule.packageName, rule.permission, rule.state) }

    /** A rule's release to default, under the same lock. */
    private fun release(rule: AppPermissionRule): AppPermissionOutcome =
        grantLock.withLock { appPermissions.release(rule.packageName, rule.permission) }

    private fun appPermissionKeys(doc: ConfigApplyPayload): Set<String> =
        doc.appPermissions.orEmpty().flatMap { entry ->
            entry.permissions.keys.map { permission -> appPermissionKey(entry.packageName, permission) }
        }.toSet()

    private fun appPermissionOutcome(outcome: AppPermissionOutcome): String = when (outcome) {
        AppPermissionOutcome.Applied -> ConfigOutcome.APPLIED
        AppPermissionOutcome.Unsupported -> ConfigOutcome.UNSUPPORTED
        AppPermissionOutcome.NotRequested -> NOT_REQUESTED
        AppPermissionOutcome.NotInstalled -> ConfigOutcome.failed("app not installed")
        is AppPermissionOutcome.Failed -> ConfigOutcome.failed(outcome.reason)
    }

    private data class AppPermissionRule(val packageName: String, val permission: String, val state: String) {
        val key: String get() = appPermissionKey(packageName, permission)
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
            section("policy $key") { releaseOutcome(toggles, key) }
                ?.takeIf(ConfigOutcome::isFailed)?.let { outcomes["$POLICIES$key"] = it }
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

    /**
     * Runs one section of the document. One that throws (a storage error in the kiosk state, a policy's own bug) is
     * that section's failure, reported and retried with the re-sent document; the sections after it still apply and
     * the result still reaches the server. Thrown out instead, it ended the document midway, after it was recorded
     * as delivered (#220). Cancellation still propagates.
     */
    // T is String or String?: a failed outcome is either. Exceptions only: an Error (out of memory) is not a section's.
    @Suppress("UNCHECKED_CAST", "TooGenericExceptionCaught")
    private inline fun <T : String?> section(what: String, apply: () -> T): T = try {
        apply()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ConfigOutcome.failed(e.message ?: "$what threw ${e.javaClass.simpleName}") as T
    }

    /**
     * The kiosk guard follows the kiosk wherever the device is, so one a past apply or exit couldn't finish is
     * finished here. A step the device won't take is reported on its own key, [KIOSK_GUARD], as deferred: never on
     * [KIOSK] (that would read as a configured kiosk whose exit failed, and the next apply would exit an ad-hoc
     * kiosk), and never as failed (the document would be re-sent forever for a step only the device can fix). Nor
     * for a document with a kiosk: its kiosk is up whatever the guard could set. It is tried again at every apply,
     * at boot, after an agent update and at every lock-task change.
     */
    private suspend fun reconcileKioskGuard(doc: ConfigApplyPayload, outcomes: MutableMap<String, String>) {
        val r = try {
            // A document with a kiosk armed the guard through its entry just now: that result. Any other settles it.
            if (doc.kiosk != null) kiosk.lastGuardResult() else kiosk.reconcileGuard()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            KioskResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (r is KioskResult.Failed) outcomes[KIOSK_GUARD] = ConfigOutcome.deferred(r.reason)
    }

    /**
     * Factory reset: Allow applied before this document's kiosk armed the guard (the document that enters kiosk runs
     * its policies first) is held by the guard now: asked again, so it reports deferred, as on every later apply,
     * rather than applied while the device is blocked. One held until this document ended kiosk applies now.
     */
    private fun reportHeldFactoryReset(
        doc: ConfigApplyPayload,
        toggles: ResolvedToggles,
        outcomes: MutableMap<String, String>,
    ) {
        val name = FactoryResetPolicy.CAPABILITY_KEY
        val key = "$POLICIES$name"
        if (doc.policies[name] != true || name !in toggles) return
        outcomes[key] = section(key) { toOutcome(toggles[name]?.setEnabled(true)) }
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
     * Re-run the newest document the server delivered (after boot / self-update, or when a wipe hold ends), or the
     * last saved one on a store from before delivered documents were kept. Null when there is none.
     *
     * The delivered one, not the saved one: a document with a failed section is never saved but may have changed the
     * device, e.g. set Factory reset: Block over a saved Allow (PR #227). Replaying the saved one would put the older
     * setting back. Not saved here, so a failed section is never reported applied.
     *
     * A saved document whose re-apply fails in part is marked stale, as [applyAfterInstall] does: the device no
     * longer matches its revision, so the server sees the drift and re-sends it rather than taking the device as
     * converged. An unsaved delivered one needs no mark: its revision differs from the saved one, so the check-in
     * reports it stale anyway (`reportedRevision`).
     */
    suspend fun reapplyPersisted(): ConfigApplyResult? = mutex.withLock {
        val saved = store.load()
        val doc = store.delivered ?: saved ?: return@withLock null
        applyLocked(doc, toggles(), listOfNotNull(saved, doc).distinct(), persist = false)
            .also { if (!succeeded(it) && doc.revision == saved?.revision) store.markStale() }
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
        val radio = toggles().wifiRadio() ?: return@withLock false
        // Read under the lock, not before waiting for it.
        if (!radio.selfHeal(lastCheckInOkMs())) return@withLock false
        store.markStale()
        true
    }

    companion object {
        private const val POLICIES = "policies."
        private const val KIOSK = "kiosk"

        /** The kiosk guard's own outcome, apart from [KIOSK]: never a configured kiosk's, never in `unreleased`. */
        const val KIOSK_GUARD = "kiosk.guard"
        private const val CONNECTIVITY = "connectivity"
        private const val APP_PERMISSIONS = "appPermissions."
        private const val NOT_REQUESTED = "not-requested"
        private val POWER_MODES = setOf(DeviceAction.POWER_ADAPTIVE, DeviceAction.POWER_ALWAYS_ON)

        private fun appPermissionKey(packageName: String, permission: String): String =
            "$APP_PERMISSIONS$packageName.$permission"

        private fun ruleFromKey(key: String): AppPermissionRule? {
            val parts = key.removePrefix(APP_PERMISSIONS).split(".android.permission.", limit = 2)
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
            return AppPermissionRule(parts[0], "android.permission.${parts[1]}", AppRuntimePermissions.DEFAULT)
        }

        /** Never a real revision (config.apply requires a non-blank one). */
        private const val NO_REVISION = ""

        fun succeeded(r: ConfigApplyResult): Boolean = r.outcomes.values.none(ConfigOutcome::isFailed)
    }
}

private fun ResolvedToggles.wifiRadio(): WifiRadioPolicy? = this[WifiRadioPolicy.CAPABILITY_KEY] as? WifiRadioPolicy

private fun toOutcome(o: PolicyOutcome?): String = when (o) {
    null, PolicyOutcome.Unsupported -> ConfigOutcome.UNSUPPORTED
    PolicyOutcome.Applied -> ConfigOutcome.APPLIED
    is PolicyOutcome.Failed -> ConfigOutcome.failed(o.reason)
    is PolicyOutcome.Deferred -> ConfigOutcome.deferred(o.reason)
}
