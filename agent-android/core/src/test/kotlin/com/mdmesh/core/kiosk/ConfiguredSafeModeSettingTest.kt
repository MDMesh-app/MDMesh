package com.mdmesh.core.kiosk

import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.proto.ConfigApplyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfiguredSafeModeSettingTest {

    private val store = InMemoryConfigStateStore()
    private val setting = ConfiguredSafeModeSetting(store)

    @Test
    fun `nothing applied yet means not managed`() {
        assertNull(setting.current())
    }

    @Test
    fun `the saved document's setting`() {
        store.save(ConfigApplyPayload(revision = "s1", policies = mapOf("safeBoot" to false)))
        assertEquals(false, setting.current())
    }

    @Test
    fun `the delivered document wins over the saved one, even if it failed and was never saved`() {
        store.save(ConfigApplyPayload(revision = "s1", policies = mapOf("safeBoot" to true)))
        store.delivered = ConfigApplyPayload(revision = "s2")
        assertNull("the server stopped allowing it: an old Allow must not linger", setting.current())
    }
}
