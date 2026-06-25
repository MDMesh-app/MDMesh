package com.mdmesh.core.telemetry

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.SecurityPosture
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handle: DpmHandle,
) {
    fun collect(): SecurityPosture {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val adb = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        val dev = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val unknown = runCatching {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }.getOrDefault(false)
        return SecurityPosture(
            storageEncrypted = isEncrypted(),
            deviceSecure = km.isDeviceSecure,
            adbEnabled = adb,
            devOptionsEnabled = dev,
            unknownSourcesAllowed = unknown,
            patchAgeDays = patchAgeDays(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else null,
                LocalDate.now().toEpochDay(),
            ),
            isDeviceOwner = handle.dpm.isDeviceOwnerApp(context.packageName),
        )
    }

    @Suppress("DEPRECATION")
    private fun isEncrypted(): Boolean {
        val dpm = handle.dpm
        return dpm.storageEncryptionStatus != android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED &&
            dpm.storageEncryptionStatus != android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE
    }

    companion object {
        /** Pure: days between a "yyyy-MM-dd" security patch string and now; null if absent/unparseable. */
        fun patchAgeDays(patch: String?, nowEpochDay: Long): Int? = runCatching {
            (nowEpochDay - LocalDate.parse(patch).toEpochDay()).toInt()
        }.getOrNull()
    }
}
