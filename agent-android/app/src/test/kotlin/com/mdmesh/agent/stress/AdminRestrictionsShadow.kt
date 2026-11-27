package com.mdmesh.agent.stress

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Bundle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDevicePolicyManager

/**
 * Robolectric keeps an admin's restrictions in UserManager and doesn't shadow `getUserRestrictions(admin)`, which the
 * kiosk guard reads back after each one it sets. Answers it from UserManager, for the restrictions the agent sets.
 * A test with a DevicePolicyManager shadow of its own extends this one.
 */
@Implements(DevicePolicyManager::class)
open class AdminRestrictionsShadow : ShadowDevicePolicyManager() {
    @Implementation
    protected open fun getUserRestrictions(@Suppress("UNUSED_PARAMETER") admin: ComponentName): Bundle {
        val users = ApplicationProvider.getApplicationContext<Application>().getSystemService(UserManager::class.java)
        return Bundle().apply { KEYS.forEach { putBoolean(it, users.hasUserRestriction(it)) } }
    }

    private companion object {
        val KEYS = listOf(
            UserManager.DISALLOW_APPS_CONTROL, UserManager.DISALLOW_CREATE_WINDOWS, UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_ADJUST_VOLUME,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        )
    }
}
