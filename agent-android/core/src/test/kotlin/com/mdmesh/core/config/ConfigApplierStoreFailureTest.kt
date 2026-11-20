package com.mdmesh.core.config

import android.content.ComponentName
import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.command.handlers.KioskEnterHandler
import com.mdmesh.core.kiosk.FakeController
import com.mdmesh.core.kiosk.FakeHome
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.store.StoreUnavailableException
import com.mdmesh.core.testing.fixedToggles
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigLocation
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * #220: the kiosk store failing (a storage error) fails the kiosk section only. The document used to abort midway,
 * after it was recorded as delivered and before the sections after the kiosk, with the storage library's message.
 */
class ConfigApplierStoreFailureTest {

    private object UnreadableKioskStore : KioskStateStore {
        private val eio = StoreUnavailableException("kiosk state", "read", IOException("EIO"))
        override suspend fun save(payload: KioskApplyPayload?) = throw eio
        override suspend fun load(): KioskApplyPayload? = throw eio
        override fun flow(): Flow<KioskApplyPayload?> = emptyFlow()
    }

    private val kioskApplier =
        KioskApplier(FakeController(KioskResult.Ok), UnreadableKioskStore, FakeHome(), ComponentName("a", "b")) {
            false
        }

    @Test
    fun `a kiosk store failure is the kiosk's failed outcome and the rest of the document still applies`() = runTest {
        var location: String? = null
        val config = InMemoryConfigStateStore()
        val applier = ConfigApplier(fixedToggles(), kioskApplier, { location = it }, config, setPowerMode = {})
        val doc = ConfigApplyPayload(revision = "r1", kiosk = KioskApplyPayload(), location = ConfigLocation("high"))

        val result = applier.apply(doc)

        assertEquals(ConfigOutcome.failed("kiosk state could not be read: EIO"), result.outcomes["kiosk"])
        assertEquals("high", location)
        assertEquals(ConfigOutcome.APPLIED, result.outcomes["location"])
        assertTrue("safe mode's own store read failed too, as a failure", result.outcomes.keys.any { "safeBoot" in it })
        assertEquals("not saved: a section failed", null, config.revision())
    }

    @Test
    fun `a boot re-apply that fails in part marks the configuration stale, so the server re-sends it`() = runTest {
        val config = InMemoryConfigStateStore()
        config.save(ConfigApplyPayload(revision = "r1", kiosk = KioskApplyPayload()))
        val applier = ConfigApplier(fixedToggles(), kioskApplier, {}, config, setPowerMode = {})

        val result = applier.reapplyPersisted()

        assertEquals(ConfigOutcome.failed("kiosk state could not be read: EIO"), result?.outcomes?.get("kiosk"))
        assertEquals(ConfigStateStore.STALE_REVISION, config.revision())
    }

    @Test
    fun `kiosk enter command fails with the named store error`() = runTest {
        val dispatcher = CommandDispatcher(listOf(KioskEnterHandler(kioskApplier)))
        val result = dispatcher.dispatch(
            CommandEnvelope(commandId = "c1", type = "kiosk.enter", issuedAt = "2026-11-18T00:00:00Z"),
        )
        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals("kiosk state could not be read: EIO", result.detail)
    }
}
