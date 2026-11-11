package com.mdmesh.proto

import kotlinx.serialization.Serializable

/** Full device census reported on check-in. All sub-objects except [dynamic] are nullable: a
 *  collector that lacks its permission (or fails) contributes null, so the snapshot is always valid. */
@Serializable
data class TelemetrySnapshot(
    val dynamic: DynamicState,
    val hardware: HardwareInfo? = null,
    val identity: IdentityInfo? = null,
    val security: SecurityPosture? = null,
)

@Serializable
data class HardwareInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val osRelease: String,
    val sdkInt: Int,
    val securityPatch: String?,
    val buildFingerprint: String,
    val abis: List<String>,
    val totalRamBytes: Long,
    val totalStorageBytes: Long,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val densityDpi: Int,
)

/** Sensitive: identity ATTRIBUTES only — the device identity remains the server-issued id. */
@Serializable
data class IdentityInfo(
    val serial: String? = null,
    val imei: List<String> = emptyList(),
    val imsi: List<String> = emptyList(),
    val iccid: List<String> = emptyList(),
    val phoneNumber: List<String> = emptyList(),
)

@Serializable
data class DynamicState(
    val batteryPct: Int,
    val batteryHealth: String,
    val batteryTempC: Double,
    val batteryVoltageMv: Int,
    val chargingSource: String, // none | ac | usb | wireless
    val freeStorageBytes: Long,
    val freeRamBytes: Long,
    val networkType: String, // wifi | cellular | none
    val wifiSsid: String? = null,
    val wifiBssid: String? = null,
    val ipAddress: String? = null,
    val wifiRssi: Int? = null,
    val wifiLinkSpeedMbps: Int? = null,
    val cellularOperator: String? = null,
    val cellularSignalLevel: Int? = null,
    val roaming: Boolean = false,
    val screenOn: Boolean,
    val locked: Boolean,
    val kioskActive: Boolean,
    val foregroundApp: String? = null,
    val uptimeMs: Long,
    val lastBootAt: Long,
    /** Last-known (or freshly-fixed, in active mode) device location; null without permission/fix. */
    val location: LocationDto? = null,
)

/** A device location fix. [capturedAt] is the fix's own timestamp (epoch millis), not report time. */
@Serializable
data class LocationDto(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val provider: String? = null,
    val capturedAt: Long,
)

@Serializable
data class SecurityPosture(
    val storageEncrypted: Boolean,
    val deviceSecure: Boolean,
    val adbEnabled: Boolean,
    val devOptionsEnabled: Boolean,
    val unknownSourcesAllowed: Boolean,
    val patchAgeDays: Int?,
    val isDeviceOwner: Boolean,
    /**
     * 1.8: when the OS update the device is waiting to install was first offered (epoch millis). Null when none is
     * pending, or when the agent can't tell (API < 26, not Device Owner, the call failed).
     */
    val pendingSystemUpdateAt: Long? = null,
    /** 1.8: whether that update is a security patch; null when the platform doesn't know, or none is pending. */
    val pendingSystemUpdateSecurityPatch: Boolean? = null,
)
