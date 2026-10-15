package com.mdmesh.policy

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPrefsLiftRestoreStoreTest {

    /** In-memory SharedPreferences: only what the store uses. [commitResult] simulates a failed disk write. */
    private class FakePrefs : SharedPreferences {
        val values = HashMap<String, Any?>()
        var commitResult = true
        override fun contains(key: String) = values.containsKey(key)
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
            Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
            Unit

        inner class Editor : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            override fun putBoolean(key: String, value: Boolean) = also { puts[key] = value }
            override fun remove(key: String) = also { removes += key }
            override fun commit(): Boolean {
                if (!commitResult) return false
                removes.forEach { values.remove(it) }
                values.putAll(puts)
                return true
            }
            override fun apply() { commit() }
            override fun putString(key: String, value: String?) = also { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = also { puts[key] = values }
            override fun putInt(key: String, value: Int) = also { puts[key] = value }
            override fun putLong(key: String, value: Long) = also { puts[key] = value }
            override fun putFloat(key: String, value: Float) = also { puts[key] = value }
            override fun clear() = also { removes += values.keys }
        }
    }

    @Test fun `absent, put, read back by a new instance, removed`() {
        val prefs = FakePrefs()
        assertNull(SharedPrefsLiftRestoreStore(prefs).get("userAppInstall"))
        SharedPrefsLiftRestoreStore(prefs).put("userAppInstall", false)
        // A new process reads the same file: both values of the flag survive (false is not "absent").
        assertEquals(false, SharedPrefsLiftRestoreStore(prefs).get("userAppInstall"))
        SharedPrefsLiftRestoreStore(prefs).put("userAppInstall", true)
        assertEquals(true, SharedPrefsLiftRestoreStore(prefs).get("userAppInstall"))
        SharedPrefsLiftRestoreStore(prefs).remove("userAppInstall")
        assertNull(SharedPrefsLiftRestoreStore(prefs).get("userAppInstall"))
    }

    @Test fun `keys are independent`() {
        val store = SharedPrefsLiftRestoreStore(FakePrefs())
        store.put("userAppInstall", true)
        assertNull(store.get("userAppUninstall"))
    }

    @Test fun `a failed write refuses to lift`() {
        val prefs = FakePrefs().apply { commitResult = false }
        val e = runCatching { SharedPrefsLiftRestoreStore(prefs).put("userAppInstall", true) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        // ...so the policy never lifts without a record: the clear is not attempted.
        var cleared = false
        val policy = TemporaryUserRestrictionPolicy(
            "userAppInstall", { true }, { true }, { cleared = cleared || !it }, SharedPrefsLiftRestoreStore(prefs),
        )
        val opError = runCatching { kotlinx.coroutines.runBlocking { policy.withAllowed { } } }.exceptionOrNull()
        assertTrue(opError is IllegalStateException); assertTrue("no clear without a record", !cleared)
    }

    @Test fun `a failed remove throws and keeps the record`() {
        val prefs = FakePrefs()
        val store = SharedPrefsLiftRestoreStore(prefs)
        store.put("userAppInstall", true)
        prefs.commitResult = false
        assertTrue(runCatching { store.remove("userAppInstall") }.exceptionOrNull() is IllegalStateException)
        assertEquals(true, store.get("userAppInstall"))
    }
}
