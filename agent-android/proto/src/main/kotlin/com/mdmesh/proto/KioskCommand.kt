package com.mdmesh.proto

import kotlinx.serialization.Serializable

/**
 * Payload of the `kiosk.enter` command (and the locally-persisted last-applied kiosk state).
 *
 * Every field defaults so old agents tolerate new keys and a minimal `{ }` payload is valid.
 *
 * @property mode `"single"` (pin one app) or `"launcher"` (show the allowed-apps home grid).
 * @property allowedPackages packages allowlisted for lock-task (the agent's own package is always added).
 * @property pinPackage in `single` mode, the app to launch + pin.
 * @property features lock-task UI feature toggles (see [com.mdmesh.kiosk.lockTaskFeatures]).
 * @property exitMode `"gesture"` | `"visible"` | `"remote"` — how a technician leaves kiosk on device.
 * @property password admin password required by the on-device exit (gesture/visible).
 * @property theme launcher appearance.
 */
@Serializable
data class KioskApplyPayload(
    val mode: String = "launcher",
    val allowedPackages: List<String> = emptyList(),
    val pinPackage: String? = null,
    val features: KioskFeaturesDto = KioskFeaturesDto(),
    val exitMode: String = "gesture",
    val password: String? = null,
    val theme: KioskThemeDto = KioskThemeDto(),
)

@Serializable
data class KioskFeaturesDto(
    val home: Boolean? = null,
    val recents: Boolean? = null,
    val notifications: Boolean? = null,
    val systemInfo: Boolean? = null,
    val keyguard: Boolean? = null,
    val lockButtons: Boolean? = null,
)

@Serializable
data class KioskThemeDto(
    val backgroundColor: String? = null,
    val textColor: String? = null,
    val iconSize: String? = null,
)
