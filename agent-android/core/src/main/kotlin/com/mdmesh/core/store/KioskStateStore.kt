package com.mdmesh.core.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.ProtocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Persists the last-applied [KioskApplyPayload] so the agent can re-enter kiosk on boot
 * (the agent never receives the Headwind config — only commands — so there is nothing else
 * to re-read). [save]`(null)` clears it on `kiosk.exit`.
 */
interface KioskStateStore {
    /** @throws StoreUnavailableException the storage failed */
    suspend fun save(payload: KioskApplyPayload?)

    /** @throws StoreUnavailableException the storage failed */
    suspend fun load(): KioskApplyPayload?

    /**
     * Cold stream of the current kiosk payload (or `null` when not in kiosk), emitting on every
     * change. The launcher collects this so a background `kiosk.enter`/`kiosk.exit` command — which
     * runs in the check-in service, not the foreground activity — is reflected on screen
     * immediately (enter shows the grid/pinned app; exit drops to idle and unpins).
     *
     * Never fails: while the storage can't be read it is read again with backoff and nothing is emitted, so a kiosk
     * on screen stays on screen (an error is never taken for "no kiosk", which would unpin it).
     */
    fun flow(): Flow<KioskApplyPayload?>
}

/**
 * A store's file could not be read or written: a storage error (a corrupt file is recovered, never reported). The
 * message names the store and the operation, so a command that fails on it says what failed ("kiosk state could not
 * be read: …") rather than the storage library's own words.
 */
class StoreUnavailableException(what: String, operation: String, cause: IOException) :
    IOException("$what could not be $operation: ${cause.message}", cause)

/** [store] is [AgentDataStores.kiosk] (mirrored, recovered when corrupt). */
class DataStoreKioskStateStore(private val store: DataStore<Preferences>) : KioskStateStore {

    override suspend fun save(payload: KioskApplyPayload?) {
        try {
            store.edit { if (payload == null) it.remove(KEY) else it[KEY] = encode(payload) }
        } catch (e: IOException) {
            throw StoreUnavailableException(WHAT, "saved", e)
        }
    }

    override suspend fun load(): KioskApplyPayload? {
        val raw = try {
            store.data.first()[KEY]
        } catch (e: IOException) {
            throw StoreUnavailableException(WHAT, "read", e)
        }
        return raw?.let(::decode)
    }

    override fun flow(): Flow<KioskApplyPayload?> =
        store.data.map { prefs -> prefs[KEY]?.let(::decode) }.retryOnStorageError(WHAT)

    companion object {
        private val KEY = stringPreferencesKey("kiosk_payload")
        private const val WHAT = "kiosk state"

        /** The store's contents holding [payload]: what the kiosk store recovers to from the configured kiosk. */
        fun prefsOf(payload: KioskApplyPayload): Preferences = preferencesOf(KEY to encode(payload))

        private fun encode(payload: KioskApplyPayload): String =
            ProtocolJson.json.encodeToString(KioskApplyPayload.serializer(), payload)

        private fun decode(raw: String): KioskApplyPayload? = runCatching {
            ProtocolJson.json.decodeFromString(KioskApplyPayload.serializer(), raw)
        }.getOrNull()
    }
}

/** In-memory [KioskStateStore] for unit tests. */
class InMemoryKioskStateStore(initial: KioskApplyPayload? = null) : KioskStateStore {
    private val state = MutableStateFlow(initial)

    /** As saved: a StateFlow keeps the old value for an equal new one (an image's new URL, same SHA-256). */
    private var latest = initial

    override suspend fun save(payload: KioskApplyPayload?) {
        latest = payload
        state.value = payload
    }

    override suspend fun load(): KioskApplyPayload? = latest

    override fun flow(): Flow<KioskApplyPayload?> = state
}
