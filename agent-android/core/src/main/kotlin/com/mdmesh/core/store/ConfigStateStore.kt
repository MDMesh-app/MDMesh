package com.mdmesh.core.store

import android.content.Context
import android.content.SharedPreferences
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigAppPermissions
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.ProtocolJson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Last desired-state document the agent fully applied (see ConfigApplier). Its revision is reported in every
 * check-in so the server can tell drift from convergence. After boot the newest [delivered] document is re-applied,
 * this one only on a store from before that was kept.
 * Synchronous (SharedPreferences) on purpose: the state snapshot is built on a non-suspending path.
 */
interface ConfigStateStore {
    fun save(doc: ConfigApplyPayload)
    fun load(): ConfigApplyPayload?
    fun revision(): String?
    fun clear()

    /** Observe the latest delivered configuration's kiosk, falling back to saved state on older installations. */
    fun configuredKioskFlow(): Flow<KioskApplyPayload?>

    /**
     * Keep the document but report [STALE_REVISION], so the server sees drift and re-sends config.apply (used after
     * the device undid part of it on its own, e.g. a Wi-Fi radio self-heal, or a wipe lifted Factory reset: Block).
     * The boot re-apply still replays the newest delivered document. A missing revision would not do: the server keeps
     * the last one it stored.
     */
    fun markStale()

    /**
     * The last document the server delivered, applied in full or not (one with a failed section is never saved, but
     * may have changed the device). The next document releases its keys too, and a Wi-Fi radio self-heal hold
     * belongs to its revision: only a different revision ends the hold.
     */
    var delivered: ConfigApplyPayload?

    /** Result keys (`policies.<key>`, `kiosk`) whose release or kiosk exit failed: the next document retries them. */
    var unreleased: Set<String>
    /** Active app-permission rules survive documents from an older server that omit the optional section. */
    var activeAppPermissions: List<ConfigAppPermissions>

    /**
     * The revision for which a Wi-Fi radio self-heal hold was last ended (a changed configuration ends it). Kept apart
     * from [delivered], so a hold that could not be ended when its revision arrived (the `wifiRadio` check threw) is
     * ended when that revision is re-sent. Null on a store written before this existed.
     */
    var holdEndedFor: String?

    companion object {
        /** Well-formed (64 hex) but never a real revision, so it never matches the configuration. */
        val STALE_REVISION = "0".repeat(64)
    }
}

/**
 * The revision the check-in reports as applied: [ConfigStateStore.STALE_REVISION] while the newest delivered
 * document is not the saved one (a section of it failed) or none is saved, else the saved revision.
 *
 * Never null: the server keeps the last revision it stored for a missing one, so a device whose store was lost would
 * show in sync with a configuration it no longer has and never be sent it again.
 *
 * The boot re-apply replays the newest delivered document, so the saved revision alone would claim a document the
 * device no longer follows. After a revert to exactly the saved document the server would see it in sync and never
 * re-send it, while every boot replayed the newer one. Stale, the server re-sends its current document: at once when
 * that differs from the last config.apply it sent (a revert), after its usual backoff otherwise.
 * [ConfigStateStore.revision] itself is left as it is.
 */
fun ConfigStateStore.reportedRevision(): String {
    val saved = revision() ?: return ConfigStateStore.STALE_REVISION
    val newest = delivered?.revision ?: saved
    return if (newest == saved) saved else ConfigStateStore.STALE_REVISION
}

/** A newer document with kiosk disabled must win even if an unrelated section failed to apply. */
fun ConfigStateStore.configuredKiosk(): KioskApplyPayload? = (delivered ?: load())?.kiosk

object ConfigStateCodec {
    fun encode(doc: ConfigApplyPayload): String = ProtocolJson.json.encodeToString(ConfigApplyPayload.serializer(), doc)
    fun decode(raw: String?): ConfigApplyPayload? = raw?.let {
        runCatching { ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), it) }.getOrNull()
    }
}

class SharedPrefsConfigStateStore(context: Context) : ConfigStateStore {
    private val prefs = context.getSharedPreferences("mdm_config", Context.MODE_PRIVATE)
    override fun configuredKioskFlow(): Flow<KioskApplyPayload?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_DOC || key == KEY_DELIVERED) trySend(configuredKiosk())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(configuredKiosk())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    override fun save(doc: ConfigApplyPayload) {
        prefs.edit().putString(KEY_DOC, ConfigStateCodec.encode(doc)).putString(KEY_REV, doc.revision).commit()
    }
    override fun load(): ConfigApplyPayload? = ConfigStateCodec.decode(prefs.getString(KEY_DOC, null))
    override fun revision(): String? = prefs.getString(KEY_REV, null)
    override fun clear() {
        prefs.edit()
            .remove(KEY_DOC)
            .remove(KEY_REV)
            .remove(KEY_DELIVERED)
            .remove(KEY_UNRELEASED)
            .remove(KEY_APP_PERMISSIONS)
            .remove(KEY_HOLD_ENDED)
            .commit()
    }
    override fun markStale() { prefs.edit().putString(KEY_REV, ConfigStateStore.STALE_REVISION).commit() }
    override var delivered: ConfigApplyPayload?
        get() = ConfigStateCodec.decode(prefs.getString(KEY_DELIVERED, null))
        set(v) { prefs.edit().putString(KEY_DELIVERED, v?.let(ConfigStateCodec::encode)).commit() }
    override var unreleased: Set<String>
        get() = prefs.getStringSet(KEY_UNRELEASED, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(KEY_UNRELEASED, v.toSet()).commit() }
    override var activeAppPermissions: List<ConfigAppPermissions>
        get() = ConfigStateCodec.decode(prefs.getString(KEY_APP_PERMISSIONS, null))?.appPermissions.orEmpty()
        set(v) {
            val state = ConfigApplyPayload(revision = "", appPermissions = v)
            prefs.edit().putString(KEY_APP_PERMISSIONS, ConfigStateCodec.encode(state)).commit()
        }
    override var holdEndedFor: String?
        get() = prefs.getString(KEY_HOLD_ENDED, null)
        set(v) { prefs.edit().putString(KEY_HOLD_ENDED, v).commit() }
    private companion object {
        const val KEY_DOC = "doc"; const val KEY_REV = "revision"; const val KEY_DELIVERED = "deliveredDoc"
        const val KEY_UNRELEASED = "unreleased"
        const val KEY_APP_PERMISSIONS = "appPermissions"
        const val KEY_HOLD_ENDED = "holdEndedFor"
    }
}

class InMemoryConfigStateStore : ConfigStateStore {
    private var doc: ConfigApplyPayload? = null
    private var rev: String? = null
    private val configuredKioskState = MutableStateFlow<KioskApplyPayload?>(null)
    override fun configuredKioskFlow(): Flow<KioskApplyPayload?> = configuredKioskState
    override fun save(doc: ConfigApplyPayload) {
        this.doc = doc; rev = doc.revision; configuredKioskState.value = configuredKiosk()
    }
    override fun load(): ConfigApplyPayload? = doc
    override fun revision(): String? = rev
    override fun clear() {
        doc = null
        rev = null
        delivered = null
        unreleased = emptySet()
        activeAppPermissions = emptyList()
        holdEndedFor = null
        configuredKioskState.value = configuredKiosk()
    }
    override fun markStale() { rev = ConfigStateStore.STALE_REVISION }
    override var delivered: ConfigApplyPayload? = null
        set(value) { field = value; configuredKioskState.value = configuredKiosk() }
    override var unreleased: Set<String> = emptySet()
    override var activeAppPermissions: List<ConfigAppPermissions> = emptyList()
    override var holdEndedFor: String? = null
}
