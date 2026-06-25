package com.mdmesh.core.device

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.mdmesh.core.sync.HardwareIdSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Permission-free stable device id for de-duplicating enrollments.
 *
 * Prefers [DevicePolicyManager.getEnrollmentSpecificId] (API 31+, available to a Device Owner
 * with no permission and stable across a factory reset for *our* DPC) and falls back to
 * `Settings.Secure.ANDROID_ID`. No new `<uses-permission>` — keeps the Play-Protect
 * permission-minimization posture (ADR 0005). Never throws; returns null if both are blank.
 */
@Singleton
class HardwareIdCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) : HardwareIdSource {

    @SuppressLint("HardwareIds")
    override fun get(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                dpm?.enrollmentSpecificId
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
