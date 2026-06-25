package com.mdmesh.core.store

import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KioskStateStoreTest {

    @Test
    fun `save then load returns the payload`() = runBlocking {
        val store = InMemoryKioskStateStore()
        val p = KioskApplyPayload(mode = "single", pinPackage = "com.x")
        store.save(p)
        assertEquals(p, store.load())
    }

    @Test
    fun `save null clears`() = runBlocking {
        val store = InMemoryKioskStateStore(KioskApplyPayload())
        store.save(null)
        assertNull(store.load())
    }
}
