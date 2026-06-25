package com.mdmesh.policy.camera

import android.os.Build
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.wifi.DpmHandle

/**
 * Camera strategy for API 21+ (the minimum at which a Device Owner may call
 * [android.app.admin.DevicePolicyManager.setCameraDisabled]). Since this module's
 * `minSdk` is 24 the API is always present; the guard keeps the SDK-discipline
 * pattern uniform and protects against a lowered floor later.
 *
 * All raw DPM calls are confined here; the inversion (`enabled` -> `disabled`) is
 * applied exactly once, at the boundary.
 */
internal class CameraDisablePolicy(
    private val handle: DpmHandle,
) : CameraPolicy {

    override val capabilityKey: String = CameraPolicy.CAPABILITY_KEY

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        // DPM takes "disabled"; feature ON (enabled=true) => not disabled.
        handle.dpm.setCameraDisabled(handle.admin, !enabled)
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "camera setEnabled failed") }
}
