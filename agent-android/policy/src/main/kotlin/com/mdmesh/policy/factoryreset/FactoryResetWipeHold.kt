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
 * process, never indefinitely. One instance per process (Hilt singleton).
 */
class FactoryResetWipeHold(
    private val store: WipeHoldStore = InMemoryWipeHoldStore(),
    private val bootId: () -> Long = { 0L },
) {

    @Volatile
    private var heldInProcess = false

    val held: Boolean
        get() = heldInProcess || savedForThisBoot()

    /** Called by `device.wipe` just before the wipe call, under the configuration lock. */
    fun hold() {
        heldInProcess = true
        bootId().takeIf { it != 0L }?.let { store.heldBoot = it }
    }

    /** Called by `device.wipe` when the wipe call threw: the device is not resetting. */
    fun release() {
        heldInProcess = false
        store.heldBoot = null
    }

    private fun savedForThisBoot(): Boolean {
        val saved = store.heldBoot ?: return false
        val boot = bootId()
        val current = boot != 0L && saved == boot
        if (!current) store.heldBoot = null
        return current
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

/** Where [FactoryResetWipeHold] saves the boot its hold was set in; null when none. */
interface WipeHoldStore {
    var heldBoot: Long?
}

class InMemoryWipeHoldStore : WipeHoldStore {
    @Volatile
    override var heldBoot: Long? = null
}

/** [WipeHoldStore] in SharedPreferences (commit, not apply: it must be on disk before the wipe call). */
class SharedPrefsWipeHoldStore(context: Context) : WipeHoldStore {
    private val prefs = context.applicationContext.getSharedPreferences("mdm_wipe_hold", Context.MODE_PRIVATE)
    override var heldBoot: Long?
        get() = prefs.getLong(KEY_BOOT, 0L).takeIf { it != 0L }
        set(v) {
            prefs.edit().apply { if (v == null) remove(KEY_BOOT) else putLong(KEY_BOOT, v) }.commit()
        }
    private companion object {
        const val KEY_BOOT = "heldBoot"
    }
}
