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

/**
 * The same kiosk as [other], whatever either looks like: the apps, mode, exit settings and lock-task features match.
 * Only these are the kiosk's identity; its [KioskApplyPayload.theme] (colours, icon size, background image, fit,
 * focal point) is appearance, changed in place: never a re-entry, a relaunch of the pinned app or a lock-task
 * restart. Lock task depends on nothing in the theme.
 */
fun KioskApplyPayload.sameKioskAs(other: KioskApplyPayload?): Boolean =
    other != null && copy(theme = KioskThemeDto()) == other.copy(theme = KioskThemeDto())

@Serializable
data class KioskFeaturesDto(
    val home: Boolean? = null,
    val recents: Boolean? = null,
    val notifications: Boolean? = null,
    val systemInfo: Boolean? = null,
    val keyguard: Boolean? = null,
    val lockButtons: Boolean? = null,
    /**
     * 1.4: block volume changes while in kiosk (`DISALLOW_ADJUST_VOLUME`). Android also MUTES the device while that
     * restriction is set, which is why this is its own opt-in rather than part of [lockButtons] (power menu only).
     */
    val lockVolume: Boolean? = null,
)

@Serializable
data class KioskThemeDto(
    val backgroundColor: String? = null,
    val textColor: String? = null,
    val iconSize: String? = null,
    /** A server-hosted, SHA-256-pinned image for the launcher background. */
    val backgroundImage: KioskBackgroundImageDto? = null,
)

/**
 * Optional kiosk image. Old agents ignore this additive key; new agents fall back to color on any failure.
 *
 * Two are the same image when their SHA-256 is (in any case): the [url] is only where to fetch it, and a server
 * that moves (a new base URL) must cause no re-download, no re-entry into the kiosk and no flash of the colour.
 */
@Serializable
data class KioskBackgroundImageDto(
    val url: String,
    val sha256: String,
    /** 1.10: `fit` draws the whole image, letterboxed with the background colour; absent (or anything else) = fill. */
    val fit: String? = null,
    /** 1.10: the point (0..1 across the image) a fill crop keeps in view; absent = the centre. */
    val focalX: Double? = null,
    /** 1.10: 0..1 down the image; absent = the centre. */
    val focalY: Double? = null,
) {
    /** The whole image, letterboxed (protocol 1.10 `fit`); otherwise fill. */
    val letterboxed: Boolean get() = fit == FIT

    val focalXOrCentre: Double get() = focal(focalX)
    val focalYOrCentre: Double get() = focal(focalY)

    /** The same image drawn the same way: SHA-256 (any case), fit mode and focal point; the URL is not compared. */
    override fun equals(other: Any?): Boolean =
        other is KioskBackgroundImageDto && key() == other.key()

    override fun hashCode(): Int = key().hashCode()

    private fun key() = listOf(sha256.trim().lowercase(), letterboxed, focalXOrCentre, focalYOrCentre)
}

private const val FIT = "fit"
private const val CENTRE = 0.5

private fun focal(v: Double?): Double = if (v == null || v.isNaN()) CENTRE else v.coerceIn(0.0, 1.0)
