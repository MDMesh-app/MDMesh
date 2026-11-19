package com.mdmesh.policy.factoryreset

import com.mdmesh.policy.PolicyOutcome

/**
 * Set once a wipe is accepted, so Factory reset: Block is not put back before the device resets.
 *
 * `wipeDevice`/`wipeData` can return before the reboot into recovery. A configuration apply after
 * that would re-add the block, and some vendor recoveries then refuse to format (#222). One instance
 * per process (Hilt singleton); a new process starts unheld.
 */
class FactoryResetWipeHold {

    @Volatile
    var held: Boolean = false
        private set

    /** Called by `device.wipe` just before the wipe call, under the configuration lock. */
    fun hold() {
        held = true
    }

    /** Called by `device.wipe` when the wipe call threw: the device is not resetting. */
    fun release() {
        held = false
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
