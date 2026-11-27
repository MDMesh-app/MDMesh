package com.mdmesh.policy.factoryreset

import com.mdmesh.policy.PolicyOutcome

/**
 * Whether kiosk mode holds Factory reset: Block on right now. The kiosk guard (`KioskGuard` in `:core`) sets the
 * block while the device is in kiosk and, when kiosk ends, puts back what the configuration says. While it holds it,
 * an Allow from any writer (`config.apply`, the boot re-apply, `policy.apply`) waits for the kiosk to end, and
 * whatever was asked last ([remember]) is what the device gets then, unless the configuration manages the setting.
 */
fun interface FactoryResetKioskHold {
    fun held(): Boolean

    /**
     * A setting that reached the device while [held]: [blocked] is what to put back when kiosk ends. True only once it
     * is persisted or the configuration manages it: otherwise the exit would lose it, so it isn't reported applied.
     */
    fun remember(blocked: Boolean): Boolean = true

    companion object {
        /** No kiosk guard: for tests and wiring that have none. */
        val None = FactoryResetKioskHold { false }
    }
}

/**
 * The convergence point for every writer of Factory reset, as [WipeHeldFactoryResetPolicy] is for a wipe: while the
 * kiosk holds the block, Allow is deferred instead of applied. Block still applies.
 */
class KioskHeldFactoryResetPolicy(
    private val policy: FactoryResetPolicy,
    private val kiosk: FactoryResetKioskHold,
) : FactoryResetPolicy by policy {

    override fun setEnabled(enabled: Boolean): PolicyOutcome {
        if (!kiosk.held()) return policy.setEnabled(enabled)
        // Kept first: a setting the guard couldn't persist is reported failed (and re-sent), never applied.
        val kept = kiosk.remember(blocked = !enabled)
        val outcome = if (enabled) PolicyOutcome.Deferred(IN_KIOSK) else policy.setEnabled(false)
        return if (kept) outcome else PolicyOutcome.Failed(NOT_KEPT)
    }

    companion object {
        const val IN_KIOSK = "Factory reset stays blocked while the device is in kiosk mode."
        const val NOT_KEPT = "The device couldn't save this setting for when kiosk mode ends; it is sent again."
    }
}
