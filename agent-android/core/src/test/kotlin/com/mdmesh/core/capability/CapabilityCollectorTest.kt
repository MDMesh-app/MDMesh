package com.mdmesh.core.capability

import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.OemCapability
import com.mdmesh.proto.RemoteControlCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for GitHub #4/#5: Device-Owner status flips to true partway through QR
 * provisioning, so the collector must probe it on every collect, never capture it once.
 */
class CapabilityCollectorTest {

    private fun collector(deviceActionKeys: List<String> = listOf("lock"), isDeviceOwner: () -> Boolean) = CapabilityCollector(
        agentVersion = "0.2.5",
        agentPackage = "com.mdmesh.agent",
        isDeviceOwner = isDeviceOwner,
        policyKeys = { listOf("wifi", "bluetooth") },
        remoteControl = { RemoteControlCapability() },
        oem = { OemCapability() },
        deviceOwnerAppManagementKeys = listOf("silentInstall"),
        deviceActionKeys = deviceActionKeys,
        buildInfo = BuildInfo(sdkInt = 33, release = "13", manufacturer = "Test", model = "Unit"),
    )

    @Test
    fun `advertises silentInstall once device owner is granted after first collect`() {
        var deviceOwner = false
        var probes = 0
        val collector = collector { probes++; deviceOwner }

        val before = collector.collect("dev-1")
        deviceOwner = true // provisioning completes between two check-ins
        val after = collector.collect("dev-1")

        assertFalse(before.device.isDeviceOwner)
        assertTrue(before.capabilities.appManagement.isEmpty())
        assertTrue(after.device.isDeviceOwner)
        assertEquals(listOf("silentInstall"), after.capabilities.appManagement)
        assertEquals(2, probes)
    }

    @Test
    fun `matrix carries injected build info and probe outputs`() {
        val m = collector { true }.collect("dev-2")

        assertEquals("dev-2", m.device.id)
        assertEquals(33, m.device.androidSdkInt)
        assertEquals("Unit", m.device.model)
        assertEquals(listOf("wifi", "bluetooth"), m.capabilities.policy)
        assertEquals(listOf("lock"), m.capabilities.device)
    }

    @Test
    fun `advertises configApply as a device action`() {
        val m = collector(deviceActionKeys = DeviceAction.ADVERTISED_KEYS) { true }.collect("dev-3")
        assertTrue(m.capabilities.device.contains(DeviceAction.CONFIG_APPLY_KEY))
    }
}
