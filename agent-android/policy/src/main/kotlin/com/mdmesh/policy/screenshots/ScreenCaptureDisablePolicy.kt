package com.mdmesh.policy.screenshots

import android.os.Build
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.wifi.DpmHandle

/**
 * Screenshot strategy for API 21+ (Device-Owner
 * [android.app.admin.DevicePolicyManager.setScreenCaptureDisabled]). With this
 * module's `minSdk` of 24 the API is always present; the guard keeps SDK
 * discipline uniform across strategies.
 */
internal class ScreenCaptureDisablePolicy(
    private val handle: DpmHandle,
) : ScreenshotsPolicy {

    override val capabilityKey: String = ScreenshotsPolicy.CAPABILITY_KEY

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        // DPM takes "disabled"; feature ON (enabled=true) => capture not disabled.
        handle.dpm.setScreenCaptureDisabled(handle.admin, !enabled)
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "screenshots setEnabled failed") }
}
