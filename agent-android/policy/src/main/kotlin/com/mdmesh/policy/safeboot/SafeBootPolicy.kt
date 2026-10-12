package com.mdmesh.policy.safeboot

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy

/**
 * Capability-abstracted control over rebooting into Safe Mode (long-press *Power off* ->
 * *Reboot to safe mode*). Safe Mode starts the device with third-party apps disabled, which is
 * the classic way out of a kiosk.
 *
 * Polarity follows [TogglePolicy] and the `config.apply` contract (`true = allowed`):
 * `setEnabled(false)` **blocks** Safe Mode, `setEnabled(true)` lifts the block. This mirrors the
 * user-restriction policies already in the module (`bluetooth`, `usbStorage`, `factoryReset`), so
 * the server-side tri-state field is inverted once, at the document boundary — exactly like
 * `disableFactoryReset` -> `factoryReset`.
 *
 * Selected by [SafeBootPolicyFactory].
 */
interface SafeBootPolicy : TogglePolicy {

    override fun setEnabled(enabled: Boolean): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "safeBoot"
    }
}
