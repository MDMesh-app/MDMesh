/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Based on lockUsbStorage in hmdm-android Utils.java and changed for MDMesh:
 * rewritten in Kotlin as a capability interface.
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 * Modifications Copyright (C) 2026 the MDMesh contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
