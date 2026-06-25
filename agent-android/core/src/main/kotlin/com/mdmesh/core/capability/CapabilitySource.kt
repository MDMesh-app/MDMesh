package com.mdmesh.core.capability

import com.mdmesh.proto.CapabilityMatrix

/**
 * Supplies the current [CapabilityMatrix]. Extracted as an interface so the sync/enroll
 * orchestration depends on an abstraction (fakeable in pure-JVM unit tests) rather than
 * on the Android-bound [CapabilityCollector].
 */
fun interface CapabilitySource {
    fun matrix(deviceId: String): CapabilityMatrix
}
