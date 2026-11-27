package com.mdmesh.kiosk

import android.Manifest
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDevicePolicyManager

/** The guard's device calls reach DevicePolicyManager as the Device Owner, read back, and never throw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [DpmKioskGuardControlTest.Restrictions::class])
class DpmKioskGuardControlTest {
    /**
     * Robolectric keeps an admin's restrictions in UserManager and doesn't shadow `getUserRestrictions(admin)`. The
     * same keys as the app tests' AdminRestrictionsShadow (another module, so a copy).
     */
    @Implements(DevicePolicyManager::class)
    class Restrictions : ShadowDevicePolicyManager() {
        @Implementation
        @Suppress("ProtectedMemberInFinalClass") // Robolectric matches the framework method's visibility
        protected fun getUserRestrictions(@Suppress("UNUSED_PARAMETER") admin: ComponentName): Bundle {
            val users = ApplicationProvider.getApplicationContext<Application>()
                .getSystemService(UserManager::class.java)
            return Bundle().apply {
                KEYS.forEach { putBoolean(it, users.hasUserRestriction(it)) }
            }
        }

        private companion object {
            val KEYS = listOf(
                UserManager.DISALLOW_APPS_CONTROL, UserManager.DISALLOW_CREATE_WINDOWS,
                UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_ADJUST_VOLUME,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            )
        }
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dpm = app.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(app, "com.mdmesh.agent.admin.AdminReceiver")
    private val control = DpmKioskGuardControl(app, dpm, admin)

    @Before fun owner() {
        shadowOf(dpm).setDeviceOwner(admin)
    }

    @Test fun `the permission policy is set and read back`() {
        assertTrue(control.setPermissionPolicy(DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT))
        assertEquals(DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT, dpm.getPermissionPolicy(admin))
        assertEquals(DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT, control.permissionPolicy())
    }

    @Test fun `an uninstall block is set, lifted and read back`() {
        assertTrue(control.setUninstallBlocked("com.kiosk", true))
        assertTrue(dpm.isUninstallBlocked(admin, "com.kiosk"))
        assertTrue(control.setUninstallBlocked("com.kiosk", false))
        assertFalse(dpm.isUninstallBlocked(admin, "com.kiosk"))
    }

    @Test fun `a restriction is added, cleared and read back from this admin`() {
        assertTrue(control.setRestriction(UserManager.DISALLOW_APPS_CONTROL, true))
        assertTrue(dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_APPS_CONTROL))
        assertEquals(true, control.holdsRestriction(UserManager.DISALLOW_APPS_CONTROL))
        assertTrue(control.setRestriction(UserManager.DISALLOW_APPS_CONTROL, false))
        assertEquals(false, control.holdsRestriction(UserManager.DISALLOW_APPS_CONTROL))
    }

    @Test fun `a grant state is set and read back`() {
        installKiosk()
        val denied = DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
        assertTrue(control.setGrantState("com.kiosk", Manifest.permission.CAMERA, denied))
        assertEquals(denied, dpm.getPermissionGrantState(admin, "com.kiosk", Manifest.permission.CAMERA))
    }

    @Test fun `only runtime permissions are listed, and an app not installed is null`() {
        installKiosk()
        assertEquals(listOf(Manifest.permission.CAMERA), control.requestedRuntimePermissions("com.kiosk"))
        assertNull(control.requestedRuntimePermissions("com.absent"))
        assertTrue("com.kiosk" in control.installedPackages().orEmpty())
    }

    @Test fun `installs from unknown sources are restricted device-wide`() {
        assertEquals(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, control.unknownSourcesRestriction)
    }

    @Test fun `not the Device Owner, nothing reaches the device`() {
        shadowOf(dpm).setDeviceOwner(null)
        assertFalse(control.isDeviceOwner())
        assertFalse(control.setPermissionPolicy(DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT))
        assertFalse(control.setUninstallBlocked("com.kiosk", true))
        assertFalse(control.setRestriction(UserManager.DISALLOW_APPS_CONTROL, true))
        assertNull(control.permissionPolicy())
        assertNull(control.holdsRestriction(UserManager.DISALLOW_APPS_CONTROL))
    }

    private fun installKiosk() {
        val pm = shadowOf(app.packageManager)
        pm.addPermissionInfo(PermissionInfo().apply {
            name = Manifest.permission.CAMERA
            protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
        })
        pm.addPermissionInfo(PermissionInfo().apply {
            name = Manifest.permission.INTERNET
            protectionLevel = PermissionInfo.PROTECTION_NORMAL
        })
        pm.installPackage(PackageInfo().apply {
            packageName = "com.kiosk"
            requestedPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.INTERNET)
        })
    }
}
