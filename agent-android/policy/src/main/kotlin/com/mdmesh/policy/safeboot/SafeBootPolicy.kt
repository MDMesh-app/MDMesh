package com.mdmesh.policy.safeboot

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy

/**
 * Capability-abstracted control over booting into Android safe mode.
 *
 * Safe mode starts the device with every downloaded app disabled — the agent included — so the
 * kiosk and everything else the agent enforces at runtime is gone until the next normal boot.
 * On a managed fleet that makes safe mode a one-gesture kiosk bypass.
 *
 * Polarity follows [TogglePolicy] and the `config.apply` contract (`true = allowed`):
 * `setEnabled(false)` **blocks** safe mode, `setEnabled(true)` lifts the block — the same shape as
 * `factoryReset`, so the server-side tri-state field (`disableSafeBoot`) is inverted once, at the
 * document boundary.
 *
 * Selected by [SafeBootPolicyFactory].
 */
interface SafeBootPolicy : TogglePolicy {

    override fun setEnabled(enabled: Boolean): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "safeBoot"
    }
}
