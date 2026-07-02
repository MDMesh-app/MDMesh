package com.mdmesh.agent.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.core.config.ServerConfigStore

/**
 * Handles `ACTION_GET_PROVISIONING_MODE` during DPC setup.
 *
 * The platform asks the DPC which provisioning mode to use; this agent is a
 * fully-managed-device (Device Owner) DPC, so it answers
 * [DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE]. No work-profile
 * mode is offered — this is a corporate-owned, fully-managed agent.
 *
 * The QR's `PROVISIONING_ADMIN_EXTRAS_BUNDLE` (enroll token + server URL) arrives on this
 * intent too. It MUST be copied into the result: AOSP preserves the original bundle when the
 * DPC omits it, but the documented contract (and TestDPC) is to return it, and OEM
 * provisioning stacks are not guaranteed to preserve it. The server URL is also persisted
 * here — the earliest possible point — as a belt-and-braces for
 * [AdminPolicyComplianceActivity].
 */
class GetProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras = adminExtras()
        ServerConfigStore(applicationContext)
            .save(extras?.getString(AdminReceiver.EXTRA_SERVER_URL))

        val result = Intent().apply {
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
            )
            extras?.let {
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, it)
            }
        }
        setResult(RESULT_OK, result)
        finish()
    }

    @Suppress("DEPRECATION") // typed getParcelableExtra is API 33+; we support minSdk 24
    private fun adminExtras(): PersistableBundle? =
        intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
}
