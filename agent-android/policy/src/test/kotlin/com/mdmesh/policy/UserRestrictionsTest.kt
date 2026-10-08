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
        assertEquals("no_factory_reset", UserRestrictions.DISALLOW_FACTORY_RESET)
    }

    @Test
    fun `factoryReset maps to the single DISALLOW_FACTORY_RESET restriction`() {
        assertEquals(
            setOf(UserRestrictions.DISALLOW_FACTORY_RESET),
            UserRestrictions.forKey("factoryReset"),
        )
    }

    @Test
    fun `app restrictions map to global flags with Device Owner minimum SDK`() {
        assertEquals(setOf("no_install_apps"), UserRestrictions.forKey("userAppInstall"))
        assertEquals(setOf("no_uninstall_apps"), UserRestrictions.forKey("userAppUninstall"))
        assertEquals(21, UserRestrictions.minSdkForKey("userAppInstall"))
        assertEquals(21, UserRestrictions.minSdkForKey("userAppUninstall"))
    }

    @Test
    fun `keys not implemented via user restrictions return null`() {
        assertNull(UserRestrictions.forKey("camera"))
        assertNull(UserRestrictions.forKey("screenshots"))
        assertNull(UserRestrictions.forKey("wifi"))
        assertNull(UserRestrictions.forKey("unknown"))
        // The registry's unimplemented FRP row is a different capability: setFactoryResetProtectionPolicy
        // (API 30) and not a user restriction, so it must not be served by this mapping.
        assertNull(UserRestrictions.forKey("factoryResetProtection"))
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
    fun `factoryReset requires only API 21 so the module floor is sufficient`() {
        val min = UserRestrictions.minSdkForKey("factoryReset")
        assertEquals(21, min)
        assertTrue("factoryReset min SDK must be <= module minSdk (24)", min!! <= 24)
    }

    @Test
    fun `min SDK is null for keys this helper does not own`() {
        assertNull(UserRestrictions.minSdkForKey("camera"))
        assertNull(UserRestrictions.minSdkForKey("factoryResetProtection"))
        assertNull(UserRestrictions.minSdkForKey("unknown"))
    }
}
