package com.mdmesh.core.store

import com.mdmesh.proto.ConfigApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigStateStoreTest {
    @Test
    fun `save load revision clear`() {
        val s = InMemoryConfigStateStore()
        assertNull(s.load()); assertNull(s.revision())
        s.save(ConfigApplyPayload(revision = "r1", configurationId = 3, policies = mapOf("wifi" to true)))
        assertEquals("r1", s.revision())
        assertEquals(mapOf("wifi" to true), s.load()?.policies)
        s.clear()
        assertNull(s.revision())
    }

    @Test
    fun `codec round-trips and rejects junk`() {
        val doc = ConfigApplyPayload(revision = "r2", configurationId = 1)
        assertEquals(doc, ConfigStateCodec.decode(ConfigStateCodec.encode(doc)))
        assertNull(ConfigStateCodec.decode("{not json"))
    }
}
