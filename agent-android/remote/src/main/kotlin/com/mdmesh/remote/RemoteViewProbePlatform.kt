package com.mdmesh.remote

/**
 * Platform split for the debug-only MediaProjection feasibility probe.
 *
 * API 29 introduced the typed `mediaProjection` foreground-service requirement.
 * The production remote-view implementation will own its complete lifecycle;
 * keeping this decision small and pure lets the probe test the S8/API-28 path
 * without bringing Android framework types into the decision itself.
 */
object RemoteViewProbePlatform {
    const val MEDIA_PROJECTION_FGS_API = 29

    fun usesTypedForegroundService(apiLevel: Int): Boolean = apiLevel >= MEDIA_PROJECTION_FGS_API
}
