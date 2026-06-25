package com.mdmesh.policy.usb

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy

/**
 * Capability-abstracted USB mass-storage control.
 *
 * `setEnabled(true)` allows USB storage (restrictions cleared); `setEnabled(false)`
 * blocks it (restrictions added). Backed by both `DISALLOW_USB_FILE_TRANSFER` and
 * `DISALLOW_MOUNT_PHYSICAL_MEDIA` (mirroring Headwind's `lockUsbStorage`). Selected
 * by [UsbStoragePolicyFactory].
 */
interface UsbStoragePolicy : TogglePolicy {

    override fun setEnabled(enabled: Boolean): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "usbStorage"
    }
}
