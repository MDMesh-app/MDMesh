package com.mdmesh.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [UserRestrictions]: the capability-key -> restriction-set
 * and capability-key -> min-SDK mappings. No Android types involved, so this runs
 * as a plain JVM test (the strategies that *call* these are DPM-bound and excluded
 * from unit tests, per the module's test policy).
 */
class UserRestrictionsTest {

    @Test
    fun `bluetooth maps to the single DISALLOW_BLUETOOTH restriction`() {
        assertEquals(
            setOf(UserRestrictions.DISALLOW_BLUETOOTH),
            UserRestrictions.forKey("bluetooth"),
        )
    }

    @Test
    fun `usbStorage maps to both file-transfer and physical-media restrictions`() {
        assertEquals(
            setOf(
                UserRestrictions.DISALLOW_USB_FILE_TRANSFER,
                UserRestrictions.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            ),
            UserRestrictions.forKey("usbStorage"),
        )
    }

    @Test
    fun `restriction string values mirror the android UserManager constants`() {
        assertEquals("no_bluetooth", UserRestrictions.DISALLOW_BLUETOOTH)
        assertEquals("no_usb_file_transfer", UserRestrictions.DISALLOW_USB_FILE_TRANSFER)
        assertEquals("no_physical_media", UserRestrictions.DISALLOW_MOUNT_PHYSICAL_MEDIA)
    }

    @Test
    fun `keys not implemented via user restrictions return null`() {
        assertNull(UserRestrictions.forKey("camera"))
        assertNull(UserRestrictions.forKey("screenshots"))
        assertNull(UserRestrictions.forKey("wifi"))
        assertNull(UserRestrictions.forKey("unknown"))
    }

    @Test
    fun `bluetooth requires API 26 because DISALLOW_BLUETOOTH is honoured from O`() {
        assertEquals(26, UserRestrictions.minSdkForKey("bluetooth"))
    }

    @Test
    fun `usbStorage requires only API 21 so the module floor is sufficient`() {
        val min = UserRestrictions.minSdkForKey("usbStorage")
        assertEquals(21, min)
        // The :policy module's minSdk is 24, so usbStorage is always honourable.
        assertTrue("usbStorage min SDK must be <= module minSdk (24)", min!! <= 24)
    }

    @Test
    fun `min SDK is null for keys this helper does not own`() {
        assertNull(UserRestrictions.minSdkForKey("camera"))
        assertNull(UserRestrictions.minSdkForKey("unknown"))
    }
}
