package com.mdmesh.core.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.kiosk.StubKioskController
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val legacyJob = Job()
private val legacyScope = CoroutineScope(Dispatchers.IO + legacyJob)
private val Context.legacyIdentity by preferencesDataStore(name = "mdm_identity", scope = legacyScope)
private val Context.legacyEnroll by preferencesDataStore(name = "mdm_enroll", scope = legacyScope)
private val Context.legacyKiosk by preferencesDataStore(name = "mdm_kiosk", scope = legacyScope)

/** An agent before the factory (0.5.0) wrote through `preferencesDataStore` delegates: the upgrade reads it as is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentDataStoresUpgradeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `files written by the 0_5_0 delegates are read unchanged by the factory`() = runBlocking {
        val kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.example.pos")
        context.legacyIdentity.edit {
            it[stringPreferencesKey("server_device_id")] = "dev_1"
            it[stringPreferencesKey("device_secret")] = "secret-1"
        }
        context.legacyEnroll.edit { it[stringPreferencesKey("enroll_token")] = "tok-1" }
        context.legacyKiosk.edit {
            it[stringPreferencesKey("kiosk_payload")] =
                ProtocolJson.json.encodeToString(KioskApplyPayload.serializer(), kiosk)
        }
        legacyJob.cancelAndJoin() // the old process ends

        val config = InMemoryConfigStateStore()
        // The production constructor.
        val stores = AgentDataStores(context, config, EventLog(context), StubKioskController())
        assertEquals("dev_1", DeviceIdStore(stores.identity).current())
        assertEquals("secret-1", DeviceIdStore(stores.identity).secret())
        assertEquals("tok-1", EnrollTokenStore(stores.enroll).token())
        assertEquals(kiosk, DataStoreKioskStateStore(stores.kiosk).load())
        assertEquals("nothing was recovered", null, config.revision())
    }
}
