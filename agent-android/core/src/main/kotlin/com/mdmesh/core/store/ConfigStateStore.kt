package com.mdmesh.core.store

import android.content.Context
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ProtocolJson

/**
 * Last desired-state document the agent fully applied (see ConfigApplier). Its revision is reported in every
 * check-in so the server can tell drift from convergence; the document itself is re-applied after boot.
 * Synchronous (SharedPreferences) on purpose: the state snapshot is built on a non-suspending path.
 */
interface ConfigStateStore {
    fun save(doc: ConfigApplyPayload)
    fun load(): ConfigApplyPayload?
    fun revision(): String?
    fun clear()

    /**
     * Keep the document (for the boot re-apply) but report [STALE_REVISION], so the server sees drift and re-sends
     * config.apply (used after the device undid part of it on its own, e.g. a Wi-Fi radio self-heal). A missing
     * revision would not do: the server keeps the last one it stored.
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

object ConfigStateCodec {
    fun encode(doc: ConfigApplyPayload): String = ProtocolJson.json.encodeToString(ConfigApplyPayload.serializer(), doc)
    fun decode(raw: String?): ConfigApplyPayload? = raw?.let {
        runCatching { ProtocolJson.json.decodeFromString(ConfigApplyPayload.serializer(), it) }.getOrNull()
    }
}

class SharedPrefsConfigStateStore(context: Context) : ConfigStateStore {
    private val prefs = context.getSharedPreferences("mdm_config", Context.MODE_PRIVATE)
    override fun save(doc: ConfigApplyPayload) {
        prefs.edit().putString(KEY_DOC, ConfigStateCodec.encode(doc)).putString(KEY_REV, doc.revision).apply()
    }
    override fun load(): ConfigApplyPayload? = ConfigStateCodec.decode(prefs.getString(KEY_DOC, null))
    override fun revision(): String? = prefs.getString(KEY_REV, null)
    override fun clear() { prefs.edit().remove(KEY_DOC).remove(KEY_REV).apply() }
    override fun markStale() { prefs.edit().putString(KEY_REV, ConfigStateStore.STALE_REVISION).commit() }
    override var delivered: ConfigApplyPayload?
        get() = ConfigStateCodec.decode(prefs.getString(KEY_DELIVERED, null))
        set(v) { prefs.edit().putString(KEY_DELIVERED, v?.let(ConfigStateCodec::encode)).commit() }
    override var unreleased: Set<String>
        get() = prefs.getStringSet(KEY_UNRELEASED, null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet(KEY_UNRELEASED, v.toSet()).commit() }
    override var holdEndedFor: String?
        get() = prefs.getString(KEY_HOLD_ENDED, null)
        set(v) { prefs.edit().putString(KEY_HOLD_ENDED, v).commit() }
    private companion object {
        const val KEY_DOC = "doc"; const val KEY_REV = "revision"; const val KEY_DELIVERED = "deliveredDoc"
        const val KEY_UNRELEASED = "unreleased"; const val KEY_HOLD_ENDED = "holdEndedFor"
    }
}

class InMemoryConfigStateStore : ConfigStateStore {
    private var doc: ConfigApplyPayload? = null
    private var rev: String? = null
    override fun save(doc: ConfigApplyPayload) { this.doc = doc; rev = doc.revision }
    override fun load(): ConfigApplyPayload? = doc
    override fun revision(): String? = rev
    override fun clear() { doc = null; rev = null }
    override fun markStale() { rev = ConfigStateStore.STALE_REVISION }
    override var delivered: ConfigApplyPayload? = null
    override var unreleased: Set<String> = emptySet()
    override var holdEndedFor: String? = null
}
