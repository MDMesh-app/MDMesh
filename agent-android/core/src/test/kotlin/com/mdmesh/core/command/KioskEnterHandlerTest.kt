package com.mdmesh.core.command

import android.content.ComponentName
import com.mdmesh.core.command.handlers.KioskEnterHandler
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskHomeSwitch
import com.mdmesh.core.kiosk.UserSetup
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KioskEnterHandlerTest {
    private class CountingController : KioskController {
        var enters = 0
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult {
            enters++
            return KioskResult.Ok
        }
        override fun exit() = KioskResult.Ok
        override fun isLocked(context: android.content.Context) = false
        override fun allowedPackages(): List<String> = emptyList()
    }
    private object NoHome : KioskHomeSwitch {
        override fun setClaimEnabled(enabled: Boolean) = Unit
        override fun showLauncher() = Unit
        override fun showOemHome() = Unit
    }
    private class Setup(private val complete: Boolean) : UserSetup {
        var armed = 0
        override fun isComplete() = complete
        override fun reapplyWhenComplete() { armed++ }
    }
    private val cmd = CommandEnvelope(
        commandId = "7", issuedAt = "2026-01-01T00:00:00Z", type = "kiosk.enter",
        payload = buildJsonObject { put("mode", "single"); put("pinPackage", "com.a") },
    )

    @Test fun `while the setup wizard is unfinished, kiosk_enter fails with the reason and enters nothing`() = runTest {
        val c = CountingController(); val store = InMemoryKioskStateStore(); val setup = Setup(complete = false)
        val handler = KioskEnterHandler(KioskApplier(c, store, NoHome, ComponentName("a", "b")) { false }, setup)
        val r = handler.handle(cmd)
        assertEquals(CommandStatus.FAILED, r.status)
        assertEquals(UserSetup.INCOMPLETE, r.detail)
        assertEquals(0, c.enters)
        assertNull(store.load())
        assertEquals("an ad-hoc payload is not persisted, so nothing is armed to replay it", 0, setup.armed)
    }

    @Test fun `once the setup wizard has finished, kiosk_enter enters`() = runTest {
        val c = CountingController()
        val applier = KioskApplier(c, InMemoryKioskStateStore(), NoHome, ComponentName("a", "b")) { false }
        val handler = KioskEnterHandler(applier, Setup(complete = true))
        assertEquals(CommandStatus.DONE, handler.handle(cmd).status)
        assertEquals(1, c.enters)
    }
}
