package com.mdmesh.core.location

import android.Manifest
import android.os.Build

/** The location permissions the agent holds, foreground first: Android grants background only on top of them. */
object LocationPermissions {
    fun forThisDevice(): List<String> = listOfNotNull(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q },
    )
}
