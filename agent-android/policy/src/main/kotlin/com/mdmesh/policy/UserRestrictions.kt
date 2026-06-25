package com.mdmesh.policy

/**
 * Pure, side-effect-free mapping from a capability key to the
 * [android.os.UserManager] `DISALLOW_*` restriction keys that implement it, plus
 * the minimum SDK at which that restriction set becomes meaningful.
 *
 * Splitting this out of the (DPM-touching) strategies makes the *decision* —
 * which restrictions, and whether this SDK supports them — independently unit
 * testable on the JVM. The strategies just call [forKey] and add/clear the
 * returned set; they hold no policy knowledge themselves.
 *
 * Restriction key strings are duplicated as literals (rather than referencing the
 * `UserManager.DISALLOW_*` constants) so this file carries no Android dependency
 * and runs under a plain JVM test. The values are part of the stable Android API.
 */
object UserRestrictions {

    // String values mirror android.os.UserManager constants exactly.
    const val DISALLOW_BLUETOOTH = "no_bluetooth"
    const val DISALLOW_USB_FILE_TRANSFER = "no_usb_file_transfer"
    const val DISALLOW_MOUNT_PHYSICAL_MEDIA = "no_physical_media"

    /**
     * The set of `DISALLOW_*` restriction keys backing a capability, or `null` if
     * the capability is not implemented via user restrictions.
     */
    fun forKey(capabilityKey: String): Set<String>? = when (capabilityKey) {
        "bluetooth" -> setOf(DISALLOW_BLUETOOTH)
        "usbStorage" -> setOf(DISALLOW_USB_FILE_TRANSFER, DISALLOW_MOUNT_PHYSICAL_MEDIA)
        else -> null
    }

    /**
     * The minimum SDK (API level) at which the restriction(s) for a capability
     * key are honoured as a Device-Owner user restriction.
     *
     *  - `DISALLOW_BLUETOOTH` was added in API 26 (O).
     *  - The USB-storage restrictions exist from API 21, so the module floor
     *    (API 24) is sufficient.
     *
     * Plain `Int` literals (not `Build.VERSION_CODES`) so this stays a pure JVM
     * function, unit-testable without the Android `Build` stub. Returns `null`
     * for keys this helper does not own.
     */
    fun minSdkForKey(capabilityKey: String): Int? = when (capabilityKey) {
        "bluetooth" -> 26
        "usbStorage" -> 21
        else -> null
    }
}
