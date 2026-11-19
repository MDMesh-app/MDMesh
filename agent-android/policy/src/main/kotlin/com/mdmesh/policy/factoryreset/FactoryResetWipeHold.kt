package com.mdmesh.policy.factoryreset

import android.content.Context
import com.mdmesh.policy.PolicyOutcome

/**
 * Set once a wipe is accepted, so Factory reset: Block is not put back before the device resets.
 *
 * `wipeDevice`/`wipeData` can return before the reboot into recovery. A configuration apply after
 * that would re-add the block, and some vendor recoveries then refuse to format (#222).
 *
 * The hold is saved with the boot it was set in ([bootId], `Settings.Global.BOOT_COUNT`), so it
 * outlives a restart of the agent process but not a reboot: a saved hold from another boot is
 * ignored and cleared. Without a boot count (0: the ROM doesn't keep it) it lasts only for this
 * process. Either way it ends after [MAX_HOLD_MS] of uptime ([nowMs], `elapsedRealtime`, which
 * clock changes don't move): a wipe that hasn't rebooted the device by then isn't coming. Commands
 * run again, the configuration (marked stale before the lift) is re-sent and puts the block back,
 * and [onExpired] reports it. One instance per process (Hilt singleton).
 */
class FactoryResetWipeHold(
    private val store: WipeHoldStore = InMemoryWipeHoldStore(),
    private val bootId: () -> Long = { 0L },
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val onExpired: () -> Unit = {},
) {

    /** Uptime at [hold] in this process; null when not held here. */
    private var heldAtMs: Long? = null

    val held: Boolean
        @Synchronized get() {
            val at = heldAtMs ?: savedForThisBoot() ?: return false
            val current = nowMs() - at < MAX_HOLD_MS
            if (!current) {
                clear()
                onExpired()
            }
            return current
        }

    /** Called by `device.wipe` just before the wipe call, under the configuration lock. */
    @Synchronized
    fun hold() {
        val at = nowMs()
        heldAtMs = at
        bootId().takeIf { it != 0L }?.let { store.saved = SavedWipeHold(it, at) }
    }

    /** Called by `device.wipe` when the wipe call threw: the device is not resetting. */
    @Synchronized
    fun release() = clear()

    private fun clear() {
        heldAtMs = null
        store.saved = null
    }

    /** The saved hold's uptime if it was set in this boot; one from another boot is cleared. */
    private fun savedForThisBoot(): Long? {
        val saved = store.saved ?: return null
        val boot = bootId()
        val current = boot != 0L && saved.boot == boot
        if (!current) store.saved = null
        return saved.atMs.takeIf { current }
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
            prefs.edit().apply {
                if (v == null) {
                    remove(KEY_BOOT)
                    remove(KEY_AT)
                } else {
                    putLong(KEY_BOOT, v.boot)
                    putLong(KEY_AT, v.atMs)
                }
            }.commit()
        }

    private companion object {
        const val KEY_BOOT = "heldBoot"
        const val KEY_AT = "heldAtMs"
    }
}
