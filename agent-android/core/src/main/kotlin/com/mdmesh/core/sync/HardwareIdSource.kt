package com.mdmesh.core.sync

/**
 * Supplies a stable, permission-free per-device identifier, sent on enroll + check-in so the
 * server can recognise a physical device that re-enrolls (and flag duplicate rows).
 *
 * Android-free by design (a `fun interface`) so the sync/enroll logic stays testable; the real
 * implementation ([com.mdmesh.core.device.HardwareIdCollector]) uses the Device-Owner
 * enrollment-specific id / ANDROID_ID. Returns null when no stable id is available.
 */
fun interface HardwareIdSource {
    fun get(): String?
}
