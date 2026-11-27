package com.mdmesh.core.store

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.proto.ConfigApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** The persisted store on the real SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPrefsConfigStateStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val doc = ConfigApplyPayload(revision = "a".repeat(64))

    /** A process that ends before an `apply()` is written: only a `commit()` is kept. */
    private class OnlyCommitsKept(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val real = super.getSharedPreferences(name, mode)
            return object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val editor = real.edit()
                    return Proxy.newProxyInstance(
                        javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java),
                    ) { proxy, method, args ->
                        if (method.name == "apply") null
                        else method.invoke(editor, *args.orEmpty()).let { if (it === editor) proxy else it }
                    } as SharedPreferences.Editor
                }
            }
        }
    }

    @Test
    fun `a lost store reports the stale revision until the re-sent document is saved`() {
        val store = SharedPrefsConfigStateStore(context)
        store.delivered = doc
        store.save(doc)
        assertEquals(doc.revision, store.reportedRevision())

        context.getSharedPreferences("mdm_config", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(ConfigStateStore.STALE_REVISION, store.reportedRevision())

        store.delivered = doc
        store.save(doc)
        assertEquals(doc.revision, store.reportedRevision())
    }

    @Test
    fun `a save and a clear survive the process ending right after`() {
        val ending = SharedPrefsConfigStateStore(OnlyCommitsKept(context))
        ending.save(doc)
        assertEquals(doc, SharedPrefsConfigStateStore(context).load())

        SharedPrefsConfigStateStore(OnlyCommitsKept(context)).clear()
        assertNull(SharedPrefsConfigStateStore(context).load())
    }
}
