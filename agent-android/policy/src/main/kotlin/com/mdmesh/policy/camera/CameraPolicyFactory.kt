package com.mdmesh.policy.camera

import com.mdmesh.policy.wifi.DpmHandle

/**
 * Selects the [CameraPolicy] strategy for the current device. There is only one
 * (the API has been stable since API 21), but the factory shape is kept for
 * uniformity: a future OEM-specific path slots in here without touching callers.
 *
 * Returns `null` when no strategy is supported (e.g. not Device Owner), in which
 * case the `camera` capability is never advertised.
 */
object CameraPolicyFactory {

    fun create(handle: DpmHandle): CameraPolicy? =
        listOf(CameraDisablePolicy(handle)).firstOrNull { it.isSupported() }
}
