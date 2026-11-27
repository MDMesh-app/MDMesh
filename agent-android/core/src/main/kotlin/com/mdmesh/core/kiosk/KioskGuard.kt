package com.mdmesh.core.kiosk

import android.Manifest
import android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
import android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_PROMPT
import android.os.UserManager
import android.util.Log
import com.mdmesh.kiosk.KioskGuardControl
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.PermissionGrantLock
import com.mdmesh.policy.factoryreset.FactoryResetKioskHold

/**
 * Answers a kiosk's permission requests itself and protects its kiosk apps. While lock task is (or may be) active:
 * permission requests are answered by the device-wide auto-grant, without asking the person; the kiosk apps can't be
 * stopped, cleared or uninstalled, nor the device reset, from the device.
 *
 * Android 12+ devices where whoever set them up declined to let the admin grant sensor permissions (location, camera,
 * microphone, body sensors) are the exception: the auto-grant can't answer those there. The guard denies the
 * background ones ([UNANSWERABLE]) for the kiosk apps; a foreground request for one is still answered by the
 * person, as Android asks it.
 *
 * While armed it holds:
 * - the device-wide runtime-permission policy on auto-grant, and the denials above where they apply;
 * - `DISALLOW_APPS_CONTROL` and an uninstall block on every kiosk app;
 * - no installs from unknown sources ([KioskGuardControl.unknownSourcesRestriction]);
 * - `DISALLOW_FACTORY_RESET`, except while a wipe the admin asked for is under way ([wiping]);
 * - `DISALLOW_CREATE_WINDOWS` (no other app draws over the kiosk).
 *
 * When kiosk ends ([disarm]) it puts back the policy the device had. If that policy asked, the grants made while in
 * kiosk are released to ordinary grants the user can revoke (not the configuration's own rules, not the agent's).
 * Factory reset goes back to what the configuration says, or, when it doesn't manage it, to what it was before.
 *
 * Every operation makes the device so and reads it back; none toggles, so each can run again any time. A [GuardRecord]
 * is saved before the device is changed and cleared only when everything is put back: a failure, or a death between
 * steps, leaves the device more locked down, never less, and the next run finishes it.
 *
 * Not thread-safe: driven only by [KioskApplier], under its lock and off the main thread (a snapshot reads every
 * app's grant states). [held] and [protects] are safe from any thread.
 *
 * @param factoryResetSetting the configuration's `factoryReset` policy: true = Allow, false = Block, null = not
 *   managed.
 * @param permissionRules the `package|permission` pairs the configuration's app permission rules name: never denied,
 *   released or reset here, those rules own them. Read under [grantLock], inside each decision.
 * @param wiping whether a wipe is under way ([com.mdmesh.policy.factoryreset.FactoryResetWipeHold]): Factory reset is
 *   then not blocked, since some vendor recoveries refuse to format past the block.
 */
// The guard's operations, each split into the steps a test can fault one by one; its inputs are the configuration's
// settings and the device's (each its own lambda, read at the moment it decides).
@Suppress("TooManyFunctions", "LongParameterList")
class KioskGuard(
    private val control: KioskGuardControl,
    private val records: GuardRecordStore,
    private val ownPackage: String,
    private val factoryResetSetting: () -> Boolean? = { null },
    private val permissionRules: () -> Set<String> = { emptySet() },
    private val wiping: () -> Boolean = { false },
    /** Shared with the configuration's rules, the other writer of grant states ([PermissionGrantLock]). */
    private val grantLock: PermissionGrantLock = PermissionGrantLock(),
    /**
     * Whether lock task is active now. A record marked releasing whose re-arm couldn't be saved still holds Factory
     * reset while the device is locked: the next process reads the older record, not what the arm set. No default:
     * a guard built without it would not hold there.
     */
    private val isLockTaskActive: () -> Boolean,
) : FactoryResetKioskHold {

    /**
     * Held while armed and lifted when kiosk ends: app control, overlays, and installs from unknown sources (blocked
     * while in kiosk). Nothing else in the agent sets them, so this admin's value before kiosk is off, and lifting them
     * restores it.
     */
    private val kioskRestrictions = KIOSK_RESTRICTIONS + control.unknownSourcesRestriction

    @Volatile
    private var cached: GuardRecord? = null

    /** Guards [cached] and the record's writes against [remember], which runs outside the kiosk lock. */
    private val recordLock = Any()

    /** A setting was [remember]ed in this process: a save from an older copy keeps it. */
    @Volatile
    private var remembered = false

    @Volatile
    private var loaded = false

    /** The last read of the record failed: unknown, not "none". */
    @Volatile
    private var unreadable = false

    /** Set while an arm runs over an unreadable record: nothing is saved (an older record must not be overwritten). */
    private var transientArm = false

    /**
     * True while the guard holds Factory reset: Block ([FactoryResetKioskHold]): armed, and not past a disarm's Factory
     * reset step, unless the device is locked (a record marked releasing then means a re-arm wasn't saved).
     */
    override fun held(): Boolean = record()?.let { !it.releasing || isLockTaskActive() } ?: unreadable

    /**
     * Whether the guard has a record (or one that can't be read): armed, or partway through a disarm a later run
     * finishes. What [KioskApplier] decides on; [held] is only about Factory reset.
     */
    fun armed(): Boolean = record() != null || unreadable

    /** Whether [pkg] is a kiosk app the guard protects (its uninstall is blocked). */
    fun protects(pkg: String): Boolean = record()?.packages?.contains(pkg) == true

    /**
     * Arms the guard for the kiosk apps [apps] (the agent's own package left out). A guard already armed keeps what
     * it saved first and protects them as well (a kiosk switch protects both until [commit]). A step that fails is
     * reported, and the others are still made.
     */
    fun arm(apps: Set<String>): KioskResult {
        if (!control.isDeviceOwner()) return KioskResult.Unsupported
        val current = record()
        // Unreadable: the device is still protected, from a record held only for this arm (never saved over the
        // one that couldn't be read), and the failure is reported.
        transientArm = current == null && unreadable
        return try {
            armSteps(apps - ownPackage, current) // the agent protects itself (it is the Device Owner)
        } finally {
            transientArm = false
        }
    }

    private fun armSteps(target: Set<String>, current: GuardRecord?): KioskResult {
        val failures = mutableListOf<String>()
        if (transientArm) failures += RECORD_NOT_READ
        var rec = current?.let { it.copy(packages = it.packages + target, releasing = false) } ?: firstRecord(target)
        // Saved before the device is changed; a re-arm that changes nothing writes nothing.
        if (rec != current && !transientArm && !save(rec)) failures += RECORD_NOT_SAVED
        if (current == null) info("armed for ${rec.packages}; permission policy was ${rec.priorPolicy}")
        setPolicy(PERMISSION_POLICY_AUTO_GRANT, failures)
        if (!control.canAdminGrantSensors()) rec = denyUnanswerable(rec, failures)
        setUninstallBlocked(rec.packages, true, failures)
        kioskRestrictions.forEach { setRestriction(it, true, failures) }
        if (!wiping()) setRestriction(FACTORY_RESET, true, failures)
        return result(failures)
    }

    /**
     * The device is now in the kiosk of [apps]: stop protecting apps of a kiosk it has left (a switch). Whatever
     * can't be released stays protected, and is released at the next commit or the disarm.
     */
    fun commit(apps: Set<String>): KioskResult {
        val rec = record()
        val gone = rec?.packages.orEmpty() - (apps - ownPackage)
        if (rec == null || gone.isEmpty() || !control.isDeviceOwner()) return KioskResult.Ok
        val failures = mutableListOf<String>()
        val kept = gone.filterNot { pkg -> control.setUninstallBlocked(pkg, false) }.toSet()
        kept.forEach { failures += "$it's uninstall block could not be lifted" }
        val theirs = rec.denied.filter { pairPackage(it) in gone }
        val released = theirs.filter(::releaseDenial).toSet()
        (theirs - released).forEach { failures += "$it could not be released" }
        if (!save(rec.copy(packages = rec.packages - gone + kept, denied = rec.denied - released))) {
            failures += RECORD_NOT_SAVED
        }
        return result(failures)
    }

    /**
     * Kiosk has ended: put back what the guard changed, then forget it. Call only once lock task is known to have
     * ended ([KioskApplier] checks). [alsoUnblock]: kiosk apps to unblock even when the record was lost.
     */
    fun disarm(alsoUnblock: Set<String> = emptySet()): KioskResult {
        if (!control.isDeviceOwner()) return KioskResult.Unsupported
        val rec = record()
        // Unknown (unreadable): nothing is undone yet.
        return if (rec == null && unreadable) result(listOf(RECORD_NOT_READ)) else disarmSteps(rec, alsoUnblock)
    }

    private fun disarmSteps(rec: GuardRecord?, alsoUnblock: Set<String>): KioskResult {
        val failures = mutableListOf<String>()
        val unblock = if (rec != null) {
            restorePermissions(rec, failures)
            rec.packages + alsoUnblock
        } else {
            // Lost: the permission policy stays as it is (0.5.x configurations don't set one) and grants stay fixed,
            // the cautious side. Every app this admin blocks is unblocked: only the guard blocks uninstalls.
            warn("disarmed without its record; the permission policy is left as it is")
            val blocked = blockedApps()
            if (blocked == null) failures += APPS_NOT_LISTED
            alsoUnblock + blocked.orEmpty()
        }
        setUninstallBlocked(unblock, false, failures)
        kioskRestrictions.forEach { setRestriction(it, false, failures) }
        val resetBefore = failures.size
        factoryResetAfterKiosk(rec)?.let { blocked ->
            // A wipe under way keeps it lifted; its own end re-applies the configuration.
            if (!(blocked && wiping())) setRestriction(FACTORY_RESET, blocked, failures)
        }
        // Factory reset is the configuration's again: from here on the guard no longer holds it, even if a later step
        // leaves the record for another run (an Allow then applies, never reported deferred on an unblocked device).
        if (rec != null && failures.size == resetBefore) save(rec.copy(releasing = true))
        // Forgotten last, and only when everything is back: otherwise the next run finishes it.
        if (failures.isEmpty() && !forget()) failures += "its record could not be cleared"
        if (failures.isEmpty()) info("disarmed")
        return result(failures)
    }

    /**
     * Outside kiosk (nothing wants the guard): disarms a guard that is armed, and also one whose record was lost but
     * whose restrictions this admin still holds. Only the guard sets [kioskRestrictions], so holding any is
     * proof the guard set them. An unreadable record is unknown: nothing is done until it can be read.
     */
    fun settleOutsideKiosk(): KioskResult {
        val armed = control.isDeviceOwner() &&
            (record() != null || unreadable || kioskRestrictions.any { control.holdsRestriction(it) == true })
        return if (armed) disarm() else KioskResult.Ok
    }

    /** Installed apps this admin blocks from uninstall (only the guard does); null when they can't be listed. */
    private fun blockedApps(): Set<String>? =
        otherApps()?.filter { control.isUninstallBlocked(it) == true }?.toSet()

    /** The policy the device had, the grants made in kiosk released if it asked, the guard's denials released. */
    private fun restorePermissions(rec: GuardRecord, failures: MutableList<String>) {
        setPolicy(rec.priorPolicy, failures)
        if (rec.priorPolicy == PERMISSION_POLICY_PROMPT && rec.fixedBefore != null) {
            releaseFixedSince(rec.fixedBefore, rec.denied, failures)
        }
        rec.denied.filterNot(::releaseDenial).forEach { failures += "$it could not be released" }
    }

    /** What Factory reset goes back to: the configuration's setting, else what it was; null: leave it as it is. */
    private fun factoryResetAfterKiosk(rec: GuardRecord?): Boolean? = when (factoryResetSetting()) {
        false -> true
        true -> false
        null -> rec?.factoryResetBefore
    }

    private fun firstRecord(target: Set<String>): GuardRecord {
        // Unreadable: taken as auto-grant, so the end of kiosk never puts a device back to asking by mistake.
        val prior = control.permissionPolicy() ?: PERMISSION_POLICY_AUTO_GRANT
        return GuardRecord(
            priorPolicy = prior,
            packages = target,
            // Only a device that asked needs grants released afterwards; the snapshot reads every app's states.
            fixedBefore = if (prior == PERMISSION_POLICY_PROMPT) fixedPairs() else null,
            factoryResetBefore = control.holdsRestriction(FACTORY_RESET) ?: true,
        )
    }

    /**
     * Every pair fixed by policy now (installed apps' runtime permissions; the agent's own are never touched). Null
     * when any part can't be read: an unknown snapshot is kept as "not taken", so no grant is released after kiosk
     * (fixed grants stay fixed, the safe side), rather than taken for "nothing was fixed".
     */
    private fun fixedPairs(): Set<String>? {
        val states = otherAppsPermissions()?.map { (pkg, perms) ->
            pkg to perms.associateWith { control.grantState(pkg, it) }
        }
        val unknown = states == null || states.any { (_, s) -> s.values.any { it == null } }
        if (unknown) warn("fixed grants not snapshotted (something couldn't be read): none are released after kiosk")
        return states?.takeUnless { unknown }?.flatMap { (pkg, s) ->
            s.filterValues { it != PERMISSION_GRANT_STATE_DEFAULT }.keys.map { pair(pkg, it) }
        }?.toSet()
    }

    /** Every app's runtime permissions but the agent's, in one listing; null when they can't be listed. */
    private fun otherAppsPermissions(): List<Pair<String, List<String>>>? =
        control.runtimePermissionsByApp()?.filterKeys { it != ownPackage }?.toList()

    /**
     * Where the admin can't grant sensor permissions, denies the [UNANSWERABLE] ones each kiosk app requests and
     * doesn't have, unless a rule fixes them. Each pair is saved before it is set, so it is released even if the
     * process dies in between, and a pair saved by a run cut off before it was set is set now.
     */
    private fun denyUnanswerable(start: GuardRecord, failures: MutableList<String>): GuardRecord {
        var rec = start
        for (pkg in rec.packages) {
            val requested = control.requestedRuntimePermissions(pkg).orEmpty()
            UNANSWERABLE.filter { it in requested }.forEach { perm -> rec = deny(rec, pkg, perm, failures) }
        }
        return rec
    }

    /** Under [grantLock], with the rules read inside it: a pair a rule owns is the rule's, and only the rule's. */
    private fun deny(rec: GuardRecord, pkg: String, perm: String, failures: MutableList<String>): GuardRecord =
        grantLock.withLock {
            val pair = pair(pkg, perm)
            if (pair in permissionRules()) handOver(rec, pair, failures) else denyFree(rec, pkg, perm, failures)
        }

    /** A rule owns [pair] now: the guard forgets it without touching it (the rule's state stands). */
    private fun handOver(rec: GuardRecord, pair: String, failures: MutableList<String>): GuardRecord {
        if (pair !in rec.denied) return rec
        val next = rec.copy(denied = rec.denied - pair)
        if (!save(next)) failures += RECORD_NOT_SAVED
        return next
    }

    private fun denyFree(rec: GuardRecord, pkg: String, perm: String, failures: MutableList<String>): GuardRecord {
        val pair = pair(pkg, perm)
        val state = control.grantState(pkg, perm)
        val ours = pair in rec.denied
        // Not ours: only one that is neither fixed (or unknown) nor granted. Ours: until the device has it.
        val free = state == PERMISSION_GRANT_STATE_DEFAULT && !control.isGranted(pkg, perm)
        val wanted = if (ours) state != PERMISSION_GRANT_STATE_DENIED else free
        if (!wanted) return rec
        val next = if (ours) rec else rec.copy(denied = rec.denied + pair)
        if (!ours && !save(next)) failures += RECORD_NOT_SAVED
        if (!control.setGrantState(pkg, perm, PERMISSION_GRANT_STATE_DENIED)) failures += "$pair could not be denied"
        return next
    }

    /**
     * Releases (to revocable grants) every pair fixed since the first arm, except rules' and the guard's own. Each app
     * under [grantLock], the rules read inside it.
     */
    private fun releaseFixedSince(before: Set<String>, denied: Set<String>, failures: MutableList<String>) {
        val apps = otherAppsPermissions() ?: return run { failures += APPS_NOT_LISTED }
        apps.forEach { (pkg, perms) ->
            grantLock.withLock {
                val skip = before + denied + permissionRules()
                perms
                    .filter { perm -> pair(pkg, perm) !in skip && isFixed(pkg, perm) }
                    .filterNot { perm -> releasePair(pair(pkg, perm)) }
                    .forEach { perm -> failures += "${pair(pkg, perm)} could not be released" }
            }
        }
    }

    /**
     * Releases one of the guard's own denials; true once the guard can forget it. A pair a rule owns now is left as
     * the rule set it (and forgotten): never reset to default under it.
     */
    private fun releaseDenial(pair: String): Boolean =
        grantLock.withLock { pair in permissionRules() || releasePair(pair) }

    private fun setPolicy(policy: Int, failures: MutableList<String>) {
        if (control.permissionPolicy() != policy && !control.setPermissionPolicy(policy)) {
            failures += "the permission policy could not be set to $policy"
        }
    }

    /** Only installed apps: an app not installed yet is protected at the arm after its install. */
    private fun setUninstallBlocked(apps: Set<String>, blocked: Boolean, failures: MutableList<String>) {
        apps.filter(::installed).filterNot { control.setUninstallBlocked(it, blocked) }
            .forEach { failures += "$it's uninstall block could not be set to $blocked" }
    }

    private fun setRestriction(key: String, on: Boolean, failures: MutableList<String>) {
        if (!control.setRestriction(key, on)) failures += "$key could not be set to $on"
    }

    private fun isFixed(pkg: String, perm: String): Boolean =
        control.grantState(pkg, perm).let { it != null && it != PERMISSION_GRANT_STATE_DEFAULT }

    private fun otherApps(): List<String>? = control.installedPackages()?.filter { it != ownPackage }

    private fun releasePair(pair: String): Boolean =
        control.setGrantState(pairPackage(pair), pair.substringAfter(SEPARATOR), PERMISSION_GRANT_STATE_DEFAULT)

    private fun installed(pkg: String) = control.requestedRuntimePermissions(pkg) != null

    /**
     * The record, read once and then kept in memory. A read that fails leaves it unknown ([unreadable]) and is tried
     * again next time: unknown is never taken for "none" (that would overwrite the prior policy, or settle a device
     * from the wrong evidence).
     */
    private fun record(): GuardRecord? {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    runCatching { records.load() }
                        .onSuccess { cached = it; loaded = true; unreadable = false }
                        .onFailure { unreadable = true; warn("its record could not be read: $it") }
                }
            }
        }
        return cached
    }

    /** Clears the record, and only then forgets it in memory. */
    private fun forget(): Boolean = synchronized(recordLock) {
        if (!runCatching { records.clear() }.getOrDefault(false)) return false
        cached = null
        return true
    }

    /**
     * Kept in memory either way: an unsaved record still guides this process, and the failure is reported. A factory
     * reset setting [remember]ed meanwhile is kept in it, whatever the caller read before.
     */
    private fun save(rec: GuardRecord): Boolean = synchronized(recordLock) {
        if (transientArm) return false
        val kept = cached?.takeIf { it.factoryResetBefore != rec.factoryResetBefore && remembered }
            ?.let { rec.copy(factoryResetBefore = it.factoryResetBefore) } ?: rec
        cached = kept
        loaded = true
        return runCatching { records.save(kept) }.getOrDefault(false)
    }

    /**
     * A factory reset setting that reached the device while the guard holds the block (a `policy.apply`, or a
     * document): kept in the record as the value to put back when kiosk ends, so it is applied then, not lost. A
     * configuration that manages the setting still has the last word at exit ([factoryResetAfterKiosk]). Called from
     * the policy's writers, outside the kiosk lock, so it takes the record's own lock. True only once persisted or
     * managed by the configuration: the caller reports anything else as failed, so it is sent again, never lost.
     */
    override fun remember(blocked: Boolean): Boolean = persist(blocked) || factoryResetSetting() != null

    private fun persist(blocked: Boolean): Boolean = synchronized(recordLock) {
        // No readable record (lost, or unreadable now): nothing would carry it to the exit, so it isn't kept.
        val before = record() ?: return false
        val next = before.copy(factoryResetBefore = blocked)
        remembered = true
        cached = next // first: a save merges from it
        // Not persisted: not kept in memory either, so this process acts on exactly what the record says.
        save(next).also { kept -> if (!kept) cached = before }
    }

    private fun result(failures: List<String>): KioskResult =
        if (failures.isEmpty()) {
            KioskResult.Ok
        } else {
            warn(failures.joinToString("; "))
            KioskResult.Failed("kiosk guard: ${failures.joinToString("; ")}")
        }

    private fun info(message: String) { runCatching { Log.i(TAG, message) } }

    private fun warn(message: String) { runCatching { Log.w(TAG, message) } }

    companion object {
        private const val TAG = "KioskGuard"
        private const val RECORD_NOT_SAVED = "its record could not be saved"
        private const val RECORD_NOT_READ = "its record could not be read"
        private const val APPS_NOT_LISTED = "the installed apps could not be listed"
        private const val SEPARATOR = "|"
        private const val FACTORY_RESET = UserManager.DISALLOW_FACTORY_RESET

        /** Held while armed, lifted when kiosk ends (nothing else in the agent sets them). */
        private val KIOSK_RESTRICTIONS = listOf(UserManager.DISALLOW_APPS_CONTROL, UserManager.DISALLOW_CREATE_WINDOWS)

        /** Sensor permissions an auto-grant can't answer where the admin can't grant sensors: denied in kiosk. */
        private val UNANSWERABLE = listOf(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            "android.permission.BODY_SENSORS_BACKGROUND", // Manifest.permission's is API 33
        )

        fun pair(pkg: String, permission: String) = "$pkg$SEPARATOR$permission"

        private fun pairPackage(pair: String) = pair.substringBefore(SEPARATOR)

        /** No guard: for tests and wiring that have none (not the Device Owner, nothing is done). */
        fun none() = KioskGuard(KioskGuardControl.None, InMemoryGuardRecordStore(), ownPackage = "") { false }
    }
}
