package com.mdmesh.core.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.deviceIdDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mdm_identity",
)

/**
 * Stores the **server-issued** opaque device id in DataStore.
 *
 * This is the agent's only identity. It is NEVER derived from IMEI / IMSI / serial —
 * those are restricted post-Android 10 and are privacy-hostile besides. The server
 * mints the id on first enrollment; we persist whatever it returns and echo it in
 * every subsequent [com.mdmesh.proto.CapabilityMatrix].
 */
class DeviceIdStore(private val context: Context) : DeviceIdentity {

    val deviceId: Flow<String?> = context.deviceIdDataStore.data
        .map { it[KEY_DEVICE_ID] }

    override suspend fun current(): String? = deviceId.first()

    override suspend fun secret(): String? =
        context.deviceIdDataStore.data.map { it[KEY_DEVICE_SECRET] }.first()

    override suspend fun saveCredentials(id: String, secret: String?) {
        context.deviceIdDataStore.edit {
            it[KEY_DEVICE_ID] = id
            if (secret != null) it[KEY_DEVICE_SECRET] = secret else it.remove(KEY_DEVICE_SECRET)
        }
    }

    suspend fun clear() {
        context.deviceIdDataStore.edit {
            it.remove(KEY_DEVICE_ID)
            it.remove(KEY_DEVICE_SECRET)
        }
    }

    private companion object {
        val KEY_DEVICE_ID = stringPreferencesKey("server_device_id")
        val KEY_DEVICE_SECRET = stringPreferencesKey("device_secret")
    }
}
