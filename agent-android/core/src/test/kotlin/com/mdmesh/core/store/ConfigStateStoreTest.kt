package com.mdmesh.core.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigAppPermissions
import com.mdmesh.proto.ConfigConnectivity
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfigStateStoreTest {
    @Test
    fun `configured kiosk flow follows delivered documents including kiosk-off and legacy saved fallback`() = runTest {
        val store = InMemoryConfigStateStore()
        assertNull(store.configuredKioskFlow().first())
        val kiosk = KioskApplyPayload(password = "staff-pin")
        store.save(ConfigApplyPayload(revision = "saved", kiosk = kiosk))
        assertEquals(kiosk, store.configuredKioskFlow().first())
        store.delivered = ConfigApplyPayload(revision = "off")
        assertNull(store.configuredKioskFlow().first())
        store.save(ConfigApplyPayload(revision = "saved", kiosk = kiosk))
        assertNull("old saved kiosk cannot override a newer delivered off", store.configuredKioskFlow().first())
    }

    @Test
    fun `save load revision clear`() {
        val s = InMemoryConfigStateStore()
        assertNull(s.load()); assertNull(s.revision())
        s.save(ConfigApplyPayload(revision = "r1", configurationId = 3, policies = mapOf("wifi" to true)))
        assertEquals("r1", s.revision())
        assertEquals(mapOf("wifi" to true), s.load()?.policies)
        s.markStale()
        assertEquals("well-formed for the server, never a real revision", 64, s.revision()?.length)
        assertEquals(mapOf("wifi" to true), s.load()?.policies)
        s.clear()
        assertNull(s.revision())
        assertNull(s.delivered)
        assertEquals(emptyList<ConfigAppPermissions>(), s.activeAppPermissions)
        assertEquals(emptySet<String>(), s.activeAppRestrictions)
        assertEquals(emptySet<String>(), s.unreleased)
    }

    @Test
    fun `app restriction packages persist across instances and reset on clear`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        for (s in listOf(InMemoryConfigStateStore(), SharedPrefsConfigStateStore(context))) {
            assertEquals(emptySet<String>(), s.activeAppRestrictions)
            s.activeAppRestrictions = setOf("hr.example.tablet", "org.example")
            s.activeAppPermissions =
                listOf(ConfigAppPermissions("org.example", mapOf("android.permission.CAMERA" to "deny")))
            assertEquals(setOf("hr.example.tablet", "org.example"), s.activeAppRestrictions)
            s.clear()
            assertEquals(emptySet<String>(), s.activeAppRestrictions)
            assertEquals(emptyList<ConfigAppPermissions>(), s.activeAppPermissions)
        }
        SharedPrefsConfigStateStore(context).activeAppRestrictions = setOf("hr.example.tablet")
        assertEquals(setOf("hr.example.tablet"), SharedPrefsConfigStateStore(context).activeAppRestrictions)
    }

    @Test
    fun `codec round-trips and rejects junk`() {
        val doc = ConfigApplyPayload(revision = "r2", configurationId = 1)
        assertEquals(doc, ConfigStateCodec.decode(ConfigStateCodec.encode(doc)))
        assertNull(ConfigStateCodec.decode("{not json"))
    }

    @Test
    fun `codec keeps connectivity, which the boot re-apply sets again`() {
        for (mode in listOf("alwaysOn", "adaptive")) {
            val doc = ConfigApplyPayload(revision = "r3", connectivity = ConfigConnectivity(mode))
            assertEquals(mode, ConfigStateCodec.decode(ConfigStateCodec.encode(doc))?.connectivity?.mode)
        }
        val unmanaged = ConfigApplyPayload(revision = "r4")
        assertNull(ConfigStateCodec.decode(ConfigStateCodec.encode(unmanaged))?.connectivity)
    }
}
