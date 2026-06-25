package com.mdmesh.policy.usb

import android.os.Build
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.UserRestrictions
import com.mdmesh.policy.wifi.DpmHandle

/**
 * USB-storage strategy via the `DISALLOW_USB_FILE_TRANSFER` +
 * `DISALLOW_MOUNT_PHYSICAL_MEDIA` user restrictions.
 *
 * Both restrictions exist from API 21, so the module floor (API 24) is always
 * sufficient; the guard keeps SDK discipline uniform. The restriction key set
 * comes from the pure [UserRestrictions] helper — both keys are added/cleared
 * together, matching Headwind's `lockUsbStorage`.
 */
internal class UsbStorageRestrictionPolicy(
    private val handle: DpmHandle,
) : UsbStoragePolicy {

    override val capabilityKey: String = UsbStoragePolicy.CAPABILITY_KEY

    private val restrictions: Set<String> =
        UserRestrictions.forKey(UsbStoragePolicy.CAPABILITY_KEY).orEmpty()

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        // Feature ON (enabled=true) => restrictions cleared; OFF => restrictions added.
        restrictions.forEach { key ->
            if (enabled) {
                handle.dpm.clearUserRestriction(handle.admin, key)
            } else {
                handle.dpm.addUserRestriction(handle.admin, key)
            }
        }
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "usbStorage setEnabled failed") }
}
