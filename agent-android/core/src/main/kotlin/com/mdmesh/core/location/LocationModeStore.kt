package com.mdmesh.core.location

import android.content.Context
import com.mdmesh.proto.DeviceAction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists how the agent captures location. Default [DeviceAction.LOCATION_PASSIVE]: read the OS's
 * last-known location each check-in (near-zero battery). [DeviceAction.LOCATION_ACTIVE] requests a
 * fresh GPS/fused fix each cycle (accurate, more battery). Set remotely via `device.locationMode`.
 */
@Singleton
class LocationModeStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("mdm_location", Context.MODE_PRIVATE)

    fun get(): String =
        prefs.getString(KEY, DeviceAction.LOCATION_PASSIVE) ?: DeviceAction.LOCATION_PASSIVE

    fun set(mode: String) {
        val normalized = if (mode == DeviceAction.LOCATION_ACTIVE) {
            DeviceAction.LOCATION_ACTIVE
        } else {
            DeviceAction.LOCATION_PASSIVE
        }
        prefs.edit().putString(KEY, normalized).apply()
    }

    fun isActive(): Boolean = get() == DeviceAction.LOCATION_ACTIVE

    private companion object { const val KEY = "mode" }
}
