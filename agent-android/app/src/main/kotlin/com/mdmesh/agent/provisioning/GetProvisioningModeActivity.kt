package com.mdmesh.agent.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/**
 * Handles `ACTION_GET_PROVISIONING_MODE` during DPC setup.
 *
 * The platform asks the DPC which provisioning mode to use; this agent is a
 * fully-managed-device (Device Owner) DPC, so it answers
 * [DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE]. No work-profile
 * mode is offered — this is a corporate-owned, fully-managed agent.
 */
class GetProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val result = Intent().apply {
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
            )
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
