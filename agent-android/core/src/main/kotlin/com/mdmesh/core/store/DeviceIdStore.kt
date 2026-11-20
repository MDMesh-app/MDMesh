package com.mdmesh.core.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Stores the **server-issued** opaque device id in DataStore.
 *
 * This is the agent's only identity. It is NEVER derived from IMEI / IMSI / serial —
 * those are restricted post-Android 10 and are privacy-hostile besides. The server
 * mints the id on first enrollment; we persist whatever it returns and echo it in
 * every subsequent [com.mdmesh.proto.CapabilityMatrix].
 *
 * [store] is [AgentDataStores.identity] (mirrored, recovered when corrupt).
 */
class DeviceIdStore(private val store: DataStore<Preferences>) : DeviceIdentity {

    /** The stored id, then each change; a storage error is retried ([retryOnStorageError]), never thrown. */
    val deviceId: Flow<String?> = store.data
        .map { it[KEY_DEVICE_ID] }
        .retryOnStorageError("device id")

    override suspend fun current(): String? = store.data.first()[KEY_DEVICE_ID]

    override suspend fun secret(): String? =
        store.data.first()[KEY_DEVICE_SECRET]

    override suspend fun saveCredentials(id: String, secret: String?) {
        store.edit {
            it[KEY_DEVICE_ID] = id
            if (secret != null) it[KEY_DEVICE_SECRET] = secret else it.remove(KEY_DEVICE_SECRET)
        }
    }

    suspend fun clear() {
        store.edit {
            it.remove(KEY_DEVICE_ID)
            it.remove(KEY_DEVICE_SECRET)
        }
    }

    private companion object {
        val KEY_DEVICE_ID = stringPreferencesKey("server_device_id")
        val KEY_DEVICE_SECRET = stringPreferencesKey("device_secret")
    }
}
