/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Ported from hmdm-android InstallUtils.java and changed for MDMesh: rewritten in
 * Kotlin, with the version rules reduced to one decision over version codes.
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

package com.mdmesh.core.install

/**
 * Pure, Android-free version-gating logic for app installs.
 *
 * Ported from Headwind's `InstallUtils.generateApplicationsForInstallList` /
 * `compareVersions` rules, distilled to a single decision over version codes:
 *
 *  - A `null` or `0` requested version code means **"any"**: install only if the app
 *    is not already present; otherwise skip (we don't reinstall an arbitrary version).
 *  - Requested **greater than** installed -> [Decision.Install] (an upgrade).
 *  - Requested **equal to** installed -> [Decision.Skip] (already at the target).
 *  - Requested **less than** installed -> [Decision.DowngradeBlocked]. PackageInstaller
 *    refuses downgrades, so retrying would loop on download+install forever; the caller
 *    must explicitly uninstall the higher version first.
 *
 * Kept as an `object` with no dependencies so it is trivially unit-testable on the JVM.
 */
object VersionPolicy {

    /** Outcome of evaluating whether an install should proceed. */
    sealed interface Decision {
        /** Proceed with the install (new app or an upgrade). */
        data object Install : Decision

        /** Do nothing; [reason] explains why (already present / same version). */
        data class Skip(val reason: String) : Decision

        /** Requested version is older than installed; caller must remove first. */
        data object DowngradeBlocked : Decision
    }

    /**
     * @param installedVersionCode the currently installed version code, or `null` if
     *   the package is not installed.
     * @param requestedVersionCode the version code the server asked for, or `null`/`0`
     *   to mean "any version".
     */
    fun shouldInstall(installedVersionCode: Long?, requestedVersionCode: Long?): Decision {
        val installed = installedVersionCode

        // "Any version" request: install only if missing.
        if (requestedVersionCode == null || requestedVersionCode == 0L) {
            return if (installed == null) {
                Decision.Install
            } else {
                Decision.Skip("already installed (version code $installed); request specifies no version")
            }
        }

        // Not yet installed: always install the requested version.
        if (installed == null) return Decision.Install

        return when {
            requestedVersionCode > installed -> Decision.Install
            requestedVersionCode == installed ->
                Decision.Skip("already at requested version code $requestedVersionCode")
            else -> Decision.DowngradeBlocked
        }
    }
}
