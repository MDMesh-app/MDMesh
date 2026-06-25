package com.mdmesh.policy.screenshots

import com.mdmesh.policy.wifi.DpmHandle

/**
 * Selects the [ScreenshotsPolicy] strategy for the current device. Single
 * candidate today; factory shape kept for uniformity. Returns `null` when no
 * strategy is supported, in which case `screenshots` is never advertised.
 */
object ScreenshotsPolicyFactory {

    fun create(handle: DpmHandle): ScreenshotsPolicy? =
        listOf(ScreenCaptureDisablePolicy(handle)).firstOrNull { it.isSupported() }
}
