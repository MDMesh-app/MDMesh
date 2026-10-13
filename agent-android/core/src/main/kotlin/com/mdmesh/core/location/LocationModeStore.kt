package com.mdmesh.core.location

import android.content.Context
import com.mdmesh.proto.DeviceAction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists how the agent captures location. Default [DeviceAction.LOCATION_PASSIVE]: read the OS's
 * last-known location each check-in (near-zero battery). [DeviceAction.LOCATION_ACTIVE] requests a
 * fresh GPS/fused fix each cycle (accurate, more battery). [DeviceAction.LOCATION_OFF] reads nothing
 * (the configuration's "Do not track"). Set remotely via `device.locationMode` or `config.apply`.
 */
@Singleton
class LocationModeStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("mdm_location", Context.MODE_PRIVATE)

    fun get(): String = normalize(prefs.getString(KEY, null))

    fun set(mode: String) {
        prefs.edit().putString(KEY, normalize(mode)).apply()
    }

    companion object {
        private const val KEY = "mode"

        /** Known modes are kept; anything else (absent, or a value from a newer server) is passive. */
        fun normalize(mode: String?): String = when (mode) {
            DeviceAction.LOCATION_ACTIVE, DeviceAction.LOCATION_OFF -> mode
            else -> DeviceAction.LOCATION_PASSIVE
        }
    }
}
