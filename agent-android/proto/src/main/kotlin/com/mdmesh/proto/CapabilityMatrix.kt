package com.mdmesh.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Device -> server. Sent on enrollment and on every check-in.
 *
 * Advertises what this agent can *actually* do on this device, so the server only
 * ever issues commands it knows are supported. This is the concrete form of the
 * "evolvable / capability-gated" requirement: capabilities are open string
 * registries (see `proto/registry.md`), so a 3-year-old agent on Android 9 and a
 * fresh agent on Android 16 talk to the same server without special-casing.
 *
 * Mirrors `proto/capability-matrix.schema.json`.
 */
@Serializable
data class CapabilityMatrix(
    val protocolVersion: String = ProtocolJson.PROTOCOL_VERSION,
    val agent: AgentInfo,
    val device: DeviceInfo,
    val capabilities: Capabilities,
)

@Serializable
data class AgentInfo(
    val version: String,
    @SerialName("package") val packageName: String,
)

/**
 * Note: [id] is the **server-issued** opaque device id (see [com.mdmesh.proto]
 * docs). It is NEVER an IMEI/IMSI/serial — those are restricted post-Android 10
 * and must not be used as identity.
 */
@Serializable
data class DeviceInfo(
    val id: String,
    val androidSdkInt: Int,
    val androidRelease: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val isDeviceOwner: Boolean,
)

@Serializable
data class Capabilities(
    /** Policy capability keys this device supports, e.g. `wifi`, `kioskLockTask`. */
    val policy: List<String> = emptyList(),
    /** App-management keys, e.g. `silentInstall`, `splitApk`. */
    val appManagement: List<String> = emptyList(),
    /** Device-action keys this agent supports, e.g. `lock`, `wipe`. Flattened server-side to `device.<key>`. */
    val device: List<String> = emptyList(),
    val remoteControl: RemoteControlCapability = RemoteControlCapability(),
    val oem: OemCapability = OemCapability(),
)

@Serializable
data class RemoteControlCapability(
    val tier: String = RemoteControlTier.NONE,
    val screenCapture: Boolean = false,
    val inputInjection: Boolean = false,
    val transport: List<String> = emptyList(),
)

/** Open string registry for remote-control tiers. See `proto/README.md`. */
object RemoteControlTier {
    const val NONE = "none"
    const val VIEW = "view"
    const val CONTROL = "control"
}

@Serializable
data class OemCapability(
    val vendor: String = "generic",
    val knox: Boolean = false,
)
