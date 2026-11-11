package com.mdmesh.policy.location

import com.mdmesh.policy.wifi.DpmHandle

/** Returns a strategy only where Android exposes Device Owner location-services control (API 30+). */
object LocationServicesPolicyFactory {

    fun create(handle: DpmHandle): LocationServicesPolicy? =
        listOf(LocationServicesDpmPolicy(handle)).firstOrNull { it.isSupported() }
}
