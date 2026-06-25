package com.mdmesh.policy.camera

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy

/**
 * Capability-abstracted camera control.
 *
 * `setEnabled(true)` re-enables the camera; `setEnabled(false)` disables it.
 * Backed by [android.app.admin.DevicePolicyManager.setCameraDisabled], whose
 * argument is inverted (it takes *disabled*, not *enabled*). The single concrete
 * strategy ([CameraDisablePolicy]) is selected by [CameraPolicyFactory].
 */
interface CameraPolicy : TogglePolicy {

    override fun setEnabled(enabled: Boolean): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "camera"
    }
}
