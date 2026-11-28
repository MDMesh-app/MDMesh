package com.mdmesh.core.kiosk

/**
 * Test enrollments only: the QR's admin extra [EXTRA] set to "true" has the agent turn USB debugging on as it takes
 * over (`Settings.Global.ADB_ENABLED`, which a Device Owner may set), so an enrollment can be followed over adb from
 * its first minute, kiosk included. The computer's key still has to be accepted on the device. Never in a production
 * QR: it leaves debugging on.
 */
object EnrollmentDebugging {
    const val EXTRA = "com.mdmesh.ENABLE_ADB"

    /** Whether [extra] asks for debugging: "true", nothing else. */
    fun asked(extra: String?): Boolean = extra?.trim().equals("true", ignoreCase = true)
}
