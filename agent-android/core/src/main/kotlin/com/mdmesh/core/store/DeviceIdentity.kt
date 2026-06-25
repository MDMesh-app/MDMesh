package com.mdmesh.core.store

/**
 * The agent's persisted, server-issued credentials: the opaque device id and the
 * per-device secret (presented as a bearer token on check-in). An interface (implemented
 * by [DeviceIdStore]) so enroll/sync logic is unit-testable without Android's DataStore.
 */
interface DeviceIdentity {
    suspend fun current(): String?

    /** The per-device check-in secret, or null if not yet enrolled. */
    suspend fun secret(): String?

    /** Persist the device id and its secret together (atomically, post-enrollment). */
    suspend fun saveCredentials(id: String, secret: String?)
}
