package com.mdmesh.proto

import kotlinx.serialization.Serializable

/**
 * Payload of `config.apply` (proto/payloads/config-apply.schema.json): the server-computed desired state of the
 * device's configuration. Every field defaults so a newer server can add keys freely.
 *
 * @property revision sha256 of the canonical document; reported back as `appliedConfigRevision` once applied.
 * @property policies only the keys the configuration manages (true = allowed/enabled).
 * @property factoryResetProtection Enhanced Factory Reset Protection (EFRP, 1.5): present = ensure the EFRP
 *   policy with this shape; absent = the configuration does not manage it (release only if the previously
 *   applied document did — see ConfigApplier).
 * @property kiosk present = ensure kiosk with this payload; absent = the configuration does not assert kiosk
 *   (exit only if the previously applied configuration did — see ConfigApplier).
 * @property location capture cadence (`passive`, `active`, or `off` since 1.4), see [DeviceAction.LOCATION_MODE].
 */
@Serializable
data class ConfigApplyPayload(
    val revision: String = "",
    val configurationId: Int? = null,
    val policies: Map<String, Boolean> = emptyMap(),
    val kiosk: KioskApplyPayload? = null,
    val location: ConfigLocation? = null,
    val factoryResetProtection: FactoryResetProtectionConfig? = null,
)

/**
 * Enhanced Factory Reset Protection (EFRP, protocol 1.5): whether a wiped device demands an authorized Google
 * account, and the account allow-list permitted to unlock it. Applied through
 * `DevicePolicyManager.setFactoryResetProtectionPolicy` (API 30+, Device Owner).
 *
 * @property enabled true = FRP required after a wipe; false = FRP explicitly disabled.
 * @property accounts Google account ids allowed to bypass FRP at setup (empty = platform default: the device's
 *   last account).
 */
@Serializable
data class FactoryResetProtectionConfig(
    val enabled: Boolean = true,
    val accounts: List<String> = emptyList(),
)

@Serializable
data class ConfigLocation(val mode: String = DeviceAction.LOCATION_PASSIVE)

/** JSON-encoded into `CommandResult.detail` (proto/payloads/config-apply-result.schema.json). */
@Serializable
data class ConfigApplyResult(
    val revision: String,
    /** keys: `policies.<key>`, `kiosk`, `location`, `factoryResetProtection` → [ConfigOutcome] strings. */
    val outcomes: Map<String, String>,
)

object ConfigOutcome {
    const val APPLIED = "applied"
    const val UNSUPPORTED = "unsupported"
    fun failed(reason: String): String = "failed: $reason"

    /** 1.4: deliberately not applied now (e.g. Wi-Fi off while Wi-Fi is the only network); does not block. */
    fun deferred(reason: String): String = "deferred: $reason"
    fun isFailed(outcome: String): Boolean = outcome.startsWith("failed")
}
