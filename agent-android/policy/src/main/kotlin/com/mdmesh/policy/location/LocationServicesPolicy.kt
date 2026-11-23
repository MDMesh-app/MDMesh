package com.mdmesh.policy.location

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.ReleasablePolicy
import com.mdmesh.policy.TogglePolicy

/**
 * Controls Android's global Location-services switch, independently of whether MDMesh collects location telemetry.
 *
 * `true` enables the service and `false` disables it. While the key is managed the device also prevents a user from
 * changing it in Settings. [release] only restores that Settings control; it deliberately leaves the last global
 * state in place, which is the meaning of configuration "Not managed".
 */
interface LocationServicesPolicy : TogglePolicy, ReleasablePolicy {

    override fun setEnabled(enabled: Boolean): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "locationServices"
    }
}
