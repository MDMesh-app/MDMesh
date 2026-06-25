package com.mdmesh.core.capability

import android.os.Build
import com.mdmesh.oem.OemAdapter
import com.mdmesh.policy.CapabilityRegistry
import com.mdmesh.proto.AgentInfo
import com.mdmesh.proto.Capabilities
import com.mdmesh.proto.CapabilityMatrix
import com.mdmesh.proto.DeviceInfo
import com.mdmesh.remote.RemoteControlTierDetector

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
    private val isDeviceOwner: Boolean,
    private val capabilityRegistry: CapabilityRegistry,
    private val remoteTierDetector: RemoteControlTierDetector,
    private val oemAdapter: OemAdapter,
    private val appManagementKeys: List<String> = emptyList(),
    private val deviceActionKeys: List<String> = emptyList(),
) : CapabilitySource {

    override fun matrix(deviceId: String): CapabilityMatrix = collect(deviceId)

    fun collect(deviceId: String): CapabilityMatrix = CapabilityMatrix(
        agent = AgentInfo(version = agentVersion, packageName = agentPackage),
        device = DeviceInfo(
            id = deviceId,
            androidSdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            isDeviceOwner = isDeviceOwner,
        ),
        capabilities = Capabilities(
            policy = capabilityRegistry.supportedPolicyKeys(),
            appManagement = appManagementKeys,
            device = deviceActionKeys,
            remoteControl = remoteTierDetector.capability(),
            oem = oemAdapter.capability(),
        ),
    )
}
