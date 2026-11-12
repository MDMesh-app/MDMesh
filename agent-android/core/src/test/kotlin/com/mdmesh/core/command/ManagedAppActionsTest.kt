package com.mdmesh.core.command

import com.mdmesh.core.apps.AppActionGuard
import com.mdmesh.core.apps.AppVisibility
import com.mdmesh.core.command.handlers.AppSetHiddenHandler
import com.mdmesh.core.command.handlers.AppUninstallHandler
import com.mdmesh.core.install.InstallOutcome
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedAppActionsTest {
    private class Visibility : AppVisibility {
        var owner = true
        var hidden = false
        var refuses = false
        var calls = 0
        override fun isDeviceOwner() = owner
        override fun isHidden(pkg: String) = hidden
        override fun setHidden(pkg: String, hidden: Boolean): Boolean {
            calls++
            if (!refuses) this.hidden = hidden
            return !refuses
        }
    }
    private fun guard(store: InMemoryKioskStateStore = InMemoryKioskStateStore()) =
        AppActionGuard("org.custom.agent", store, { it == "com.system.app" }, { setOf("com.admin.active") })
    private fun cmd(pkg: String, hidden: Boolean = true) = CommandEnvelope(
        commandId = "1", type = "app.setHidden", issuedAt = "2026-11-03T00:00:00Z",
        payload = buildJsonObject { put("packageName", pkg); put("hidden", hidden) },
    )

    @Test fun `disable and enable preserve the package and report effective visibility`() = runTest {
        val platform = Visibility()
        val handler = AppSetHiddenHandler(platform, guard())
        assertEquals(CommandStatus.DONE, handler.handle(cmd("com.example.app")).status)
        assertEquals(true, platform.hidden)
        assertEquals(CommandStatus.DONE, handler.handle(cmd("com.example.app", false)).status)
        assertEquals(false, platform.hidden)
        assertEquals(2, platform.calls)
    }
    @Test fun `an OEM refusal is a failure and lack of Device Owner is unsupported`() = runTest {
        val platform = Visibility().apply { refuses = true }
        val handler = AppSetHiddenHandler(platform, guard())
        assertEquals(CommandStatus.FAILED, handler.handle(cmd("com.example.app")).status)
        platform.owner = false
        assertEquals(CommandStatus.UNSUPPORTED, handler.handle(cmd("com.example.app")).status)
        assertEquals(1, platform.calls)
    }
    @Test fun `protected apps cannot be hidden or uninstalled even by a direct command`() = runTest {
        val platform = Visibility()
        var removed = 0
        val g = guard()
        val uninstall = AppUninstallHandler({ removed++; InstallOutcome.Success }, g)
        for (pkg in listOf("org.custom.agent", "com.mdmesh.agent", "com.admin.active", "com.android.systemui")) {
            assertEquals(CommandStatus.FAILED, AppSetHiddenHandler(platform, g).handle(cmd(pkg)).status)
            assertEquals(CommandStatus.FAILED, uninstall.handle(cmd(pkg).copy(type = "app.uninstall")).status)
        }
        assertEquals(0, platform.calls)
        assertEquals(0, removed)
    }
    @Test fun `protection follows the current kiosk rather than the console snapshot`() = runTest {
        val store = InMemoryKioskStateStore()
        val g = guard(store)
        assertNull(g.reason("com.example.kiosk", uninstall = false))
        store.save(KioskApplyPayload(allowedPackages = listOf("com.example.kiosk"), pinPackage = "com.example.pinned"))
        assertNotNull(g.reason("com.example.kiosk", uninstall = false))
        assertNotNull(g.reason("com.example.pinned", uninstall = true))
        store.save(null)
        assertNull(g.reason("com.example.kiosk", uninstall = false))
    }
    @Test fun `system app removal is blocked but an ordinary system app can be hidden`() = runTest {
        assertNotNull(guard().reason("com.system.app", uninstall = true))
        assertNull(guard().reason("com.system.app", uninstall = false))
        assertNotNull(guard().reason("invalid package", uninstall = false))
    }
    @Test fun `a package protected since it was hidden can always be restored`() = runTest {
        val platform = Visibility().apply { hidden = true }
        val handler = AppSetHiddenHandler(platform, guard())
        assertEquals(CommandStatus.DONE, handler.handle(cmd("com.admin.active", false)).status)
        assertEquals(false, platform.hidden)
    }
    @Test fun `uninstall delegates only an allowed package`() = runTest {
        var removed: String? = null
        val handler = AppUninstallHandler({ removed = it; InstallOutcome.Success }, guard())
        assertEquals(CommandStatus.DONE, handler.handle(cmd("com.example.app").copy(type = "app.uninstall")).status)
        assertEquals("com.example.app", removed)
    }
}
