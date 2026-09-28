package com.mdmesh.policy.factoryreset

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy

/**
 * Capability-abstracted control over the user-initiated factory reset
 * (*Settings → System → Reset options → Erase all data*).
 *
 * Polarity follows [TogglePolicy] and the `config.apply` contract (`true = allowed`):
 * `setEnabled(false)` **blocks** the wipe, `setEnabled(true)` lifts the block. This mirrors the
 * user-restriction policies already in the module (`bluetooth`, `usbStorage`), so the server-side
 * tri-state field is inverted once, at the document boundary — exactly like
 * `disableScreenshots` -> `screenshots`.
 *
 * Selected by [FactoryResetPolicyFactory].
 */
interface FactoryResetPolicy : TogglePolicy {

    override fun setEnabled(enabled: Boolean): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "factoryReset"
    }
}
