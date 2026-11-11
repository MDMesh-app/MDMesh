package com.mdmesh.agent.service

import android.content.Intent
import com.mdmesh.proto.EventType

/** Which timeline event, if any, a broadcast the check-in service listens to stands for. */
internal object DeviceEvents {
    @Suppress("DEPRECATION")
    fun eventFor(action: String?, initialSticky: Boolean): String? {
        // A sticky broadcast is replayed as soon as the receiver registers (every service start): current state, not
        // a change, so it is not logged.
        if (initialSticky) return null
        return when (action) {
            android.net.ConnectivityManager.CONNECTIVITY_ACTION -> EventType.CONNECTIVITY
            Intent.ACTION_BATTERY_LOW -> EventType.LOW_BATTERY
            else -> null
        }
    }
}
