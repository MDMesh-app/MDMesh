/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Based on lockUsbStorage in hmdm-android Utils.java and changed for MDMesh: rewritten in
 * Kotlin as a policy strategy; the legacy Settings fallback, used below Lollipop or
 * without Device Owner, is removed.
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
