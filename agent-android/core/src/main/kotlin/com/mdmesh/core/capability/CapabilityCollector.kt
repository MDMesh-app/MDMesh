package com.mdmesh.core.capability

import android.os.Build
import com.mdmesh.proto.AgentInfo
import com.mdmesh.proto.Capabilities
import com.mdmesh.proto.CapabilityMatrix
import com.mdmesh.proto.DeviceInfo
import com.mdmesh.proto.OemCapability
import com.mdmesh.proto.RemoteControlCapability

/**
 * Builds the [CapabilityMatrix] from live device state plus the per-area
 * capability probes (policy / app-management / remote-control / OEM).
 *
 * This is the assembly point of the capability-abstraction layer: each subsystem
 * reports only what it can genuinely do on *this* device/SDK, and the union is what
 * the server is allowed to command. Nothing here reads IMEI/serial — the identity
 * is the server-issued [deviceId].
 */
class CapabilityCollector(
    private val agentVersion: String,
    private val agentPackage: String,
    private val isDeviceOwner: () -> Boolean,
    private val policyKeys: () -> List<String>,
    private val remoteControl: () -> RemoteControlCapability,
    private val oem: () -> OemCapability,
    private val deviceOwnerAppManagementKeys: List<String> = emptyList(),
    private val deviceActionKeys: List<String> = emptyList(),
    private val buildInfo: BuildInfo = BuildInfo.fromAndroid(),
) : CapabilitySource {

    override fun matrix(deviceId: String): CapabilityMatrix = collect(deviceId)

    fun collect(deviceId: String): CapabilityMatrix {
        // Probed on every collect, never captured once: during QR provisioning the first check-in
        // runs from AdminPolicyComplianceActivity while isDeviceOwnerApp() still reports false, so
        // a value read at construction time stays false for the whole process lifetime. The agent
        // then enrolls without app.silentInstall and the server gates every app.install it queues
        // — silently, until the process happens to restart.
        val deviceOwner = isDeviceOwner()
        return CapabilityMatrix(
            agent = AgentInfo(version = agentVersion, packageName = agentPackage),
            device = DeviceInfo(
                id = deviceId,
                androidSdkInt = buildInfo.sdkInt,
                androidRelease = buildInfo.release,
                manufacturer = buildInfo.manufacturer,
                model = buildInfo.model,
                isDeviceOwner = deviceOwner,
            ),
            capabilities = Capabilities(
                policy = policyKeys(),
                appManagement = if (deviceOwner) deviceOwnerAppManagementKeys else emptyList(),
                device = deviceActionKeys,
                remoteControl = remoteControl(),
                oem = oem(),
            ),
        )
    }
}

/**
 * The `android.os.Build` facts the matrix reports, as plain data so the collector is unit-testable
 * off-device (the android.jar stubs throw on `Build.MANUFACTURER` et al. in JVM tests).
 */
data class BuildInfo(
    val sdkInt: Int,
    val release: String?,
    val manufacturer: String?,
    val model: String?,
) {
    companion object {
        fun fromAndroid(): BuildInfo = BuildInfo(
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
        )
    }
}
