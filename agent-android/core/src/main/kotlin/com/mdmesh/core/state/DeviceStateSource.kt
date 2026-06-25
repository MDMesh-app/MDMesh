package com.mdmesh.core.state

import com.mdmesh.proto.AgentDeviceStateDto

/**
 * Supplies the device-state snapshot piggybacked on each check-in. Abstracted behind an interface
 * so the Android-free sync logic (and its tests) don't depend on platform APIs. Implemented by
 * [DeviceStateCollector].
 */
fun interface DeviceStateSource {
    fun snapshot(): AgentDeviceStateDto?
}
