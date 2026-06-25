package com.mdmesh.policy.bluetooth

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy

/**
 * Capability-abstracted Bluetooth control.
 *
 * `setEnabled(true)` allows Bluetooth (restriction cleared); `setEnabled(false)`
 * blocks it (restriction added). Backed by the `DISALLOW_BLUETOOTH` user
 * restriction, which is only honoured from API 26 — hence the SDK gate in the
 * concrete strategy. Selected by [BluetoothPolicyFactory].
 */
interface BluetoothPolicy : TogglePolicy {

    override fun setEnabled(enabled: Boolean): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "bluetooth"
    }
}
