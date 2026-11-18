package com.mdmesh.proto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Payload of `config.apply` (proto/payloads/config-apply.schema.json): the server-computed desired state of the
 * device's configuration. Every field defaults so a newer server can add keys freely.
 *
 * @property revision sha256 of the canonical document; reported back as `appliedConfigRevision` once applied.
 * @property policies only the keys the configuration manages (true = allowed/enabled).
 * @property kiosk present = ensure kiosk with this payload; absent = the configuration does not assert kiosk
 *   (exit only if the previously applied configuration did — see ConfigApplier).
 * @property location capture cadence (`passive`, `active`, or `off` since 1.4), see [DeviceAction.LOCATION_MODE].
 * @property connectivity (1.5) present = set the live-connection mode (`adaptive` or `alwaysOn`), the same modes as
 *   [DeviceAction.POWER_MODE]; absent = not managed, the device keeps its mode.
 */
@Serializable
data class ConfigApplyPayload(
    val revision: String = "",
    val configurationId: Int? = null,
    val policies: Map<String, Boolean> = emptyMap(),
    val kiosk: KioskApplyPayload? = null,
    val location: ConfigLocation? = null,
    val connectivity: ConfigConnectivity? = null,
    /** Null = server does not manage app permissions; empty = deliberately release all earlier overrides. */
    val appPermissions: List<ConfigAppPermissions>? = null,
    /**
     * 1.8: managed configurations (Android app restrictions). Null = the server does not manage them (keep what was
     * set); empty = clear every package set earlier.
     */
    val appRestrictions: List<ConfigAppRestrictions>? = null,
)

@Serializable
data class ConfigLocation(val mode: String = DeviceAction.LOCATION_PASSIVE)

@Serializable
data class ConfigConnectivity(val mode: String = DeviceAction.POWER_ADAPTIVE)

@Serializable
data class ConfigAppPermissions(
    val packageName: String,
    /** Exact Android runtime permission names mapped to `grant` or `deny`. */
    val permissions: Map<String, String> = emptyMap(),
)

/** One package's whole managed configuration: the bundle is replaced by exactly these keys. */
@Serializable
data class ConfigAppRestrictions(
    val packageName: String,
    val values: Map<String, ConfigRestrictionValue> = emptyMap(),
)

/**
 * A typed value: [type] is [BOOL], [STRING] or [INT] (32-bit signed), [value] the JSON boolean, string or number. A
 * type this agent doesn't know (a newer server's) still decodes, so its package can be reported unsupported.
 * Values can be secrets (device tokens): [toString] leaves the value out, so it never reaches a log line.
 */
@Serializable
data class ConfigRestrictionValue(
    val type: String = "",
    val value: JsonElement = JsonNull,
) {
    override fun toString(): String = "ConfigRestrictionValue(type=$type)"

    companion object {
        const val BOOL = "bool"
        const val STRING = "string"
        const val INT = "int"
    }
}

/** JSON-encoded into `CommandResult.detail` (proto/payloads/config-apply-result.schema.json). */
@Serializable
data class ConfigApplyResult(
    val revision: String,
    /**
     * keys: `policies.<key>`, `kiosk`, `location`, `connectivity`, `appPermissions.<package>.<permission>`,
     * `appRestrictions.<package>` → [ConfigOutcome] strings.
     */
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
