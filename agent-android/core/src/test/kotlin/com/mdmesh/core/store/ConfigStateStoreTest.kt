package com.mdmesh.core.store

import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigAppPermissions
import com.mdmesh.proto.ConfigConnectivity
import com.mdmesh.proto.ConfigSystemUpdate
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        assertEquals(emptySet<String>(), s.unreleased)
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

    @Test
    fun `codec keeps systemUpdate, which tells the next document to clear it`() {
        val windowed = ConfigSystemUpdate(ConfigSystemUpdate.WINDOWED, windowStart = 1320, windowEnd = 240)
        val doc = ConfigApplyPayload(revision = "r5", systemUpdate = windowed)
        assertEquals(windowed, ConfigStateCodec.decode(ConfigStateCodec.encode(doc))?.systemUpdate)
        assertNull(ConfigStateCodec.decode(ConfigStateCodec.encode(ConfigApplyPayload(revision = "r6")))?.systemUpdate)
    }
}
