/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Based on the system-update and user-restriction calls in hmdm-android Utils.java and
 * changed for MDMesh: rewritten in Kotlin around DpmHandle and PolicyOutcome; permission
 * policy, status bar and keyguard calls added.
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

package com.mdmesh.policy

import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.os.Build
import com.mdmesh.policy.wifi.DpmHandle

/**
 * The Device-Owner-only policy helpers that don't fit the simple on/off
 * [TogglePolicy] shape — permission auto-grant, automatic system updates, status
 * bar / keyguard lockdown, and arbitrary `DISALLOW_*` restrictions.
 *
 * These are the levers the enroll baseline and `:core` command handlers reach for
 * directly (a [TogglePolicy] is routed generically by key; these are called by
 * name). Every method is:
 *  - **Device-Owner guarded** — returns [PolicyOutcome.Unsupported] if not DO.
 *  - **SDK guarded** — returns [PolicyOutcome.Unsupported] when the API is too new
 *    for this device.
 *  - **Non-throwing** — any [DevicePolicyManager] exception becomes
 *    [PolicyOutcome.Failed]; nothing crosses the boundary as an exception.
 *
 * Partly ported from Headwind's `Utils.java`.
 */
class PolicyManager(
    private val handle: DpmHandle,
) {

    private val dpm: DevicePolicyManager get() = handle.dpm

    private fun isDeviceOwner(): Boolean =
        dpm.isDeviceOwnerApp(handle.admin.packageName)

    /**
     * Auto-grant runtime permissions to managed apps
     * ([DevicePolicyManager.setPermissionPolicy] with `PERMISSION_POLICY_AUTO_GRANT`).
     * API 23+.
     */
    fun setPermissionAutoGrant(): PolicyOutcome {
        if (!isDeviceOwner()) return PolicyOutcome.Unsupported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return PolicyOutcome.Unsupported
        return runCatching {
            dpm.setPermissionPolicy(
                handle.admin,
                DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT,
            )
            PolicyOutcome.Applied
        }.getOrElse { PolicyOutcome.Failed(it.message ?: "setPermissionAutoGrant failed") }
    }

    /**
     * Install system updates automatically as soon as they are available
     * ([SystemUpdatePolicy.createAutomaticInstallPolicy]). API 23+.
     */
    fun setSystemUpdateAutomatic(): PolicyOutcome {
        if (!isDeviceOwner()) return PolicyOutcome.Unsupported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return PolicyOutcome.Unsupported
        return runCatching {
            dpm.setSystemUpdatePolicy(
                handle.admin,
                SystemUpdatePolicy.createAutomaticInstallPolicy(),
            )
            PolicyOutcome.Applied
        }.getOrElse { PolicyOutcome.Failed(it.message ?: "setSystemUpdateAutomatic failed") }
    }

    /**
     * Hide / show the status bar ([DevicePolicyManager.setStatusBarDisabled]).
     * `disabled = true` hides it. API 23+.
     */
    fun setStatusBarDisabled(disabled: Boolean): PolicyOutcome {
        if (!isDeviceOwner()) return PolicyOutcome.Unsupported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return PolicyOutcome.Unsupported
        return runCatching {
            dpm.setStatusBarDisabled(handle.admin, disabled)
            PolicyOutcome.Applied
        }.getOrElse { PolicyOutcome.Failed(it.message ?: "setStatusBarDisabled failed") }
    }

    /**
     * Disable / enable the keyguard (lock screen)
     * ([DevicePolicyManager.setKeyguardDisabled]). `disabled = true` removes the
     * lock screen. API 23+.
     */
    fun setKeyguardDisabled(disabled: Boolean): PolicyOutcome {
        if (!isDeviceOwner()) return PolicyOutcome.Unsupported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return PolicyOutcome.Unsupported
        return runCatching {
            dpm.setKeyguardDisabled(handle.admin, disabled)
            PolicyOutcome.Applied
        }.getOrElse { PolicyOutcome.Failed(it.message ?: "setKeyguardDisabled failed") }
    }

    /**
     * Add or clear an arbitrary user restriction by its `UserManager.DISALLOW_*`
     * key. `enabled = true` *adds* the restriction (turns the lockdown on);
     * `enabled = false` clears it.
     *
     * This is the escape hatch for restriction keys not yet promoted to a typed
     * [TogglePolicy]; callers own the SDK-level semantics of the specific key.
     * API 21+.
     */
    fun applyRestriction(key: String, enabled: Boolean): PolicyOutcome {
        if (!isDeviceOwner()) return PolicyOutcome.Unsupported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return PolicyOutcome.Unsupported
        return runCatching {
            if (enabled) {
                dpm.addUserRestriction(handle.admin, key)
            } else {
                dpm.clearUserRestriction(handle.admin, key)
            }
            PolicyOutcome.Applied
        }.getOrElse { PolicyOutcome.Failed(it.message ?: "applyRestriction($key) failed") }
    }
}
