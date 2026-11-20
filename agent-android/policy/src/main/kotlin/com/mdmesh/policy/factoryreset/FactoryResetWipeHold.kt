package com.mdmesh.policy.factoryreset

import android.content.Context
import android.util.Log
import com.mdmesh.policy.PolicyOutcome

/**
 * Set once a wipe is accepted, so Factory reset: Block is not put back before the device resets.
 *
 * `wipeDevice`/`wipeData` can return before the reboot into recovery. A configuration apply after
 * that would re-add the block, and some vendor recoveries then refuse to format (#222).
 *
 * The hold is saved with the boot it was set in ([bootId], `Settings.Global.BOOT_COUNT`), so it
 * outlives a restart of the agent process but not a reboot: a saved hold from another boot is
 * ignored and cleared (as is one whose uptime is ahead of the current uptime: a reboot the boot count
 * missed). Without a boot count (0: the ROM doesn't keep it, or the read failed) it lasts only for
 * this process, and a saved one is left for a later read. A reboot is the wipe, or its failure; the
 * boot re-apply then puts the block back.
 *
 * Either way it ends after [MAX_HOLD_MS] of uptime ([nowMs], `elapsedRealtime`, which clock changes
 * don't move): a wipe that hasn't rebooted the device by then isn't coming. The expiry is noticed on
 * the first read of [held] or [remainingMs] (or [expireIfDue]) after the deadline, whichever comes
 * first, and only once: [hold] calls [onHold], which schedules such a read at the deadline
 * (`WipeHoldExpiryWorker`), so the hold ends with no command, apply or server; each check-in, command
 * and apply reads it too. Then [onExpired] re-applies the newest delivered configuration on the device (the
 * agent enqueues `ConfigReapplyWorker`), which puts the block back, and marks the configuration stale, so
 * the server re-sends it. One instance per process (Hilt
 * singleton).
 *
 * [onHold] and [onExpired] run inside this object's lock, [onExpired] possibly under the
 * configuration lock too: they must never apply the configuration themselves, only schedule work and
 * make quick writes (the agent's [onExpired] also marks the configuration stale, a synchronous commit).
 * The agent's [onHold] waits up to 5 s for WorkManager to save the scheduled check (disk IO), so
 * every read of the hold may wait that long behind a [hold].
 * Whatever they throw is dropped: a read of the hold (a command, a policy write) or the wipe never
 * fails because of them.
 */
class FactoryResetWipeHold(
    private val store: WipeHoldStore = InMemoryWipeHoldStore(),
    private val bootId: () -> Long = { 0L },
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val onHold: () -> Unit = {},
    private val onExpired: () -> Unit = {},
) {

    /** Uptime at [hold] in this process; null when not held here. */
    private var heldAtMs: Long? = null

    val held: Boolean
        get() = remainingMs != null

    /** The hold's time left, null when not held. Like [held], ends a hold past its deadline. */
    val remainingMs: Long?
        get() = read().remainingMs

    /**
     * Like [remainingMs], and says whether this read ended the hold ([Reading.endedNow]: it ran [onExpired]), for a
     * caller that does what [onExpired] does when no read of it did.
     */
    @Synchronized
    fun read(): Reading {
        val at = heldAtMs ?: savedForThisBoot() ?: return Reading(null, endedNow = false)
        val elapsed = nowMs() - at
        return when {
            elapsed < 0 -> {
                clear() // uptime restarted: a reboot, not an expiry
                Reading(null, endedNow = false)
            }
            elapsed < MAX_HOLD_MS -> Reading(MAX_HOLD_MS - elapsed, endedNow = false)
            else -> {
                clear()
                runCatching(onExpired)
                Reading(null, endedNow = true)
            }
        }
    }

    /** What [read] found: the hold's time left (null when not held), and whether that read ended it. */
    data class Reading(val remainingMs: Long?, val endedNow: Boolean)

    /** Ends the hold if it is past its deadline; for callers that need nothing else from it. */
    fun expireIfDue() {
        remainingMs
    }

    /** Called by `device.wipe` before anything is lifted, under the configuration lock. */
    @Synchronized
    fun hold() {
        val at = nowMs()
        heldAtMs = at
        bootId().takeIf { it != 0L }?.let { store.saved = SavedWipeHold(it, at) }
        runCatching(onHold)
    }

    /** Called by `device.wipe` when the wipe call threw: the device is not resetting. */
    @Synchronized
    fun release() = clear()

    private fun clear() {
        heldAtMs = null
        store.saved = null
    }

    /** The saved hold's uptime if it was set in this boot; one from another boot is cleared, none if unknown. */
    private fun savedForThisBoot(): Long? {
        val saved = store.saved
        val boot = bootId()
        if (saved == null || boot == 0L) return null
        if (saved.boot != boot) store.saved = null
        return saved.atMs.takeIf { saved.boot == boot }
    }

    companion object {
        const val MAX_HOLD_MS = 10L * 60L * 1000L
        private const val NANOS_PER_MS = 1_000_000L
    }
}

/**
 * The convergence point for every writer of the block (`config.apply`, the boot re-apply,
 * `policy.apply`): while [hold] is held, Block is deferred instead of applied. Allow still applies.
 */
class WipeHeldFactoryResetPolicy(
    private val policy: FactoryResetPolicy,
    private val hold: FactoryResetWipeHold,
) : FactoryResetPolicy by policy {

    override fun setEnabled(enabled: Boolean): PolicyOutcome =
        if (!enabled && hold.held) PolicyOutcome.Deferred(WIPING) else policy.setEnabled(enabled)

    companion object {
        const val WIPING = "A factory reset is under way."
    }
}

/** A saved hold: the boot it was set in and the uptime ([android.os.SystemClock.elapsedRealtime]) then. */
data class SavedWipeHold(val boot: Long, val atMs: Long)

/** Where [FactoryResetWipeHold] saves its hold; null when none. */
interface WipeHoldStore {
    var saved: SavedWipeHold?
}

class InMemoryWipeHoldStore : WipeHoldStore {
    @Volatile
    override var saved: SavedWipeHold? = null
}

/** [WipeHoldStore] in SharedPreferences (commit, not apply: it must be on disk before the wipe call). */
class SharedPrefsWipeHoldStore(context: Context) : WipeHoldStore {
    private val prefs = context.applicationContext.getSharedPreferences("mdm_wipe_hold", Context.MODE_PRIVATE)
    override var saved: SavedWipeHold?
        get() = prefs.getLong(KEY_BOOT, 0L).takeIf { it != 0L }?.let { SavedWipeHold(it, prefs.getLong(KEY_AT, 0L)) }
        set(v) {
            val saved = prefs.edit().apply {
                if (v == null) {
                    remove(KEY_BOOT)
                    remove(KEY_AT)
                } else {
                    putLong(KEY_BOOT, v.boot)
                    putLong(KEY_AT, v.atMs)
                }
            }.commit()
            // Unsaved, a hold dies with this process, and an apply after a restart could put the block back (#222).
            if (!saved) Log.w(TAG, if (v == null) "wipe hold not cleared on disk" else "wipe hold not saved")
        }

    private companion object {
        const val TAG = "FactoryResetWipeHold"
        const val KEY_BOOT = "heldBoot"
        const val KEY_AT = "heldAtMs"
    }
}
