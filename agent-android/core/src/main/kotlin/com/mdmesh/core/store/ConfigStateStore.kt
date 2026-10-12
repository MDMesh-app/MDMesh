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
     * Keep the document (still re-applied after boot) but stop reporting its revision until the next
     * successful [save]. Used after a self-update: the new agent may now support keys the old one reported
     * `unsupported`, and only a fresh server `config.apply` round-trip refreshes those per-key results.
     */
    fun markUnreported()
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
    override fun markUnreported() { prefs.edit().remove(KEY_REV).apply() }
    private companion object { const val KEY_DOC = "doc"; const val KEY_REV = "revision" }
}

class InMemoryConfigStateStore : ConfigStateStore {
    private var doc: ConfigApplyPayload? = null
    private var reported = true
    override fun save(doc: ConfigApplyPayload) { this.doc = doc; reported = true }
    override fun load(): ConfigApplyPayload? = doc
    override fun revision(): String? = if (reported) doc?.revision else null
    override fun clear() { doc = null }
    override fun markUnreported() { reported = false }
}
