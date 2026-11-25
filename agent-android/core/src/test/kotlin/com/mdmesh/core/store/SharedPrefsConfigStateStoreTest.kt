package com.mdmesh.core.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.proto.ConfigApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The persisted store on the real SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPrefsConfigStateStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SharedPrefsConfigStateStore(context)
    private val file = File(context.dataDir, "shared_prefs/mdm_config.xml")
    private val doc = ConfigApplyPayload(revision = "a".repeat(64))

    @Test
    fun `a lost store reports the stale revision until the re-sent document is saved`() {
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
    fun `save and clear are on disk when they return`() {
        store.save(doc)
        assertTrue(file.readText().contains(doc.revision))

        store.clear()
        assertFalse(file.readText().contains(doc.revision))
    }
}
