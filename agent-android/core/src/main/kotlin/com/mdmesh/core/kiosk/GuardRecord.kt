package com.mdmesh.core.kiosk

import android.app.admin.DevicePolicyManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * What [KioskGuard] has set on the device, and what to put back when kiosk ends. Saved before the device is changed,
 * so whatever cuts the guard off (process death, a reboot, an agent update) leaves the next start enough to finish.
 *
 * Pairs are `package|permission`.
 *
 * @property priorPolicy the runtime-permission policy the device had before the guard's first arm. Written once.
 * @property packages the kiosk apps the guard protects now.
 * @property fixedBefore the pairs that were fixed by policy before the first arm, taken only when [priorPolicy] was
 *   PROMPT (null otherwise): only pairs fixed since are released when kiosk ends.
 * @property denied the pairs the guard itself denied (devices where the admin can't grant sensor permissions).
 * @property factoryResetBefore whether this admin blocked factory reset before the first arm.
 * @property releasing a disarm has put factory reset back and stopped holding it; the record stays only for steps a
 *   later run still has to finish.
 *
 * An AUTO_DENY prior is put back as it was, without releasing grants fixed in kiosk (as with an AUTO_GRANT prior).
 * No 0.5.x path leaves a device on AUTO_DENY.
 */
data class GuardRecord(
    val priorPolicy: Int,
    val packages: Set<String>,
    val fixedBefore: Set<String>?,
    val denied: Set<String> = emptySet(),
    val factoryResetBefore: Boolean = false,
    val releasing: Boolean = false,
)

/** Where [KioskGuard] keeps its [GuardRecord]. */
interface GuardRecordStore {
    fun load(): GuardRecord?

    /** Durable before it returns true; false or a throw: not saved. */
    fun save(record: GuardRecord): Boolean

    /** Durable before it returns true; false or a throw: not cleared. */
    fun clear(): Boolean
}

class InMemoryGuardRecordStore(@Volatile var record: GuardRecord? = null) : GuardRecordStore {
    override fun load() = record
    override fun save(record: GuardRecord): Boolean { this.record = record; return true }
    override fun clear(): Boolean { record = null; return true }
}

/**
 * [GuardRecordStore] on the agent's mirrored store ([com.mdmesh.core.store.AgentDataStores.guard]), so storage damage
 * restores it from its mirror like the kiosk's own state. Each call waits for the store: callers run it off the main
 * thread ([KioskApplier] runs the guard on its IO dispatcher). A field that can't be read comes back as the cautious
 * value: the auto-grant policy as the prior one (the device is never put back to asking by mistake), factory reset
 * as blocked before, and no snapshot of fixed grants (none are released). A read or write that fails throws
 * [IOException], which [KioskGuard] takes as "unknown".
 */
class DataStoreGuardRecordStore(private val store: DataStore<Preferences>) : GuardRecordStore {

    override fun load(): GuardRecord? = runBlocking {
        val p = store.data.first()
        if (PRIOR !in p) return@runBlocking null
        GuardRecord(
            priorPolicy = p[PRIOR] ?: AUTO_GRANT,
            packages = p[PACKAGES].orEmpty(),
            fixedBefore = if (p[FIXED_TAKEN] == true) p[FIXED].orEmpty() else null,
            denied = p[DENIED].orEmpty(),
            factoryResetBefore = p[FACTORY_RESET] ?: true,
            releasing = p[RELEASING] ?: false, // unreadable: still held, the cautious side
        )
    }

    override fun save(record: GuardRecord): Boolean = runBlocking {
        store.edit { p ->
            p[PRIOR] = record.priorPolicy
            p[PACKAGES] = record.packages
            p[FIXED_TAKEN] = record.fixedBefore != null
            p[FIXED] = record.fixedBefore.orEmpty()
            p[DENIED] = record.denied
            p[FACTORY_RESET] = record.factoryResetBefore
            p[RELEASING] = record.releasing
        }
        true
    }

    override fun clear(): Boolean = runBlocking {
        store.edit { p -> KEYS.forEach { p.remove(it) } }
        true
    }

    private companion object {
        const val AUTO_GRANT = DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
        val PRIOR = intPreferencesKey("prior_policy")
        val PACKAGES = stringSetPreferencesKey("packages")
        val FIXED_TAKEN = booleanPreferencesKey("fixed_before_taken")
        val FIXED = stringSetPreferencesKey("fixed_before")
        val DENIED = stringSetPreferencesKey("denied")
        val FACTORY_RESET = booleanPreferencesKey("factory_reset_before")
        val RELEASING = booleanPreferencesKey("releasing")
        val KEYS = listOf(PRIOR, PACKAGES, FIXED_TAKEN, FIXED, DENIED, FACTORY_RESET, RELEASING)
    }
}
