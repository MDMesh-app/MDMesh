package com.mdmesh.core.command

import com.mdmesh.core.action.AndroidAppLauncher
import com.mdmesh.core.action.AppLauncher
import com.mdmesh.core.action.LaunchOutcome
import com.mdmesh.core.command.handlers.AppLaunchHandler
import com.mdmesh.proto.AppManagement
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchHandlerTest {

    private class FakeLauncher(private val outcome: LaunchOutcome = LaunchOutcome.Launched) : AppLauncher {
        val opened = mutableListOf<Pair<String, String?>>()
        override fun launch(packageName: String, activity: String?): LaunchOutcome {
            opened += packageName to activity
            return outcome
        }
    }

    private fun command(payload: JsonObject?) = CommandEnvelope(
        commandId = "c1",
        issuedAt = "2026-01-01T00:00:00Z",
        type = "app.launch",
        payload = payload,
    )

    @Test
    fun `opens the app and reports done`() = runTest {
        val launcher = FakeLauncher()
        val result = AppLaunchHandler(launcher).handle(command(buildJsonObject { put("packageName", "com.whatsapp") }))

        assertEquals(CommandStatus.DONE, result.status)
        assertEquals(listOf("com.whatsapp" to null), launcher.opened)
    }

    @Test
    fun `passes the activity when one is given, and treats a blank one as none`() = runTest {
        val launcher = FakeLauncher()
        val handler = AppLaunchHandler(launcher)
        handler.handle(command(buildJsonObject { put("packageName", "com.app"); put("activity", ".Orders") }))
        handler.handle(command(buildJsonObject { put("packageName", "com.app"); put("activity", " ") }))

        assertEquals(listOf("com.app" to ".Orders", "com.app" to null), launcher.opened)
    }

    @Test
    fun `reports failed with the reason when the app can't be opened`() = runTest {
        val launcher = FakeLauncher(LaunchOutcome.Refused("com.game is not allowed on the kiosk screen"))
        val result = AppLaunchHandler(launcher).handle(command(buildJsonObject { put("packageName", "com.game") }))

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.detail.orEmpty().contains("not allowed on the kiosk screen"))
    }

    @Test
    fun `refuses a missing payload, a bad payload and a bad package name without opening anything`() = runTest {
        val launcher = FakeLauncher()
        val handler = AppLaunchHandler(launcher)

        assertEquals(CommandStatus.FAILED, handler.handle(command(null)).status)
        assertEquals(CommandStatus.FAILED, handler.handle(command(buildJsonObject { put("activity", ".Main") })).status)
        assertEquals(
            CommandStatus.FAILED,
            handler.handle(command(buildJsonObject { put("packageName", "not a package; rm -rf") })).status,
        )
        assertTrue(launcher.opened.isEmpty())
    }

    @Test
    fun `qualifies a relative activity name`() {
        assertEquals("com.app.Orders", AndroidAppLauncher.qualified("com.app", ".Orders"))
        assertEquals("com.other.Main", AndroidAppLauncher.qualified("com.app", "com.other.Main"))
    }

    @Test
    fun `is advertised as app launch when Device Owner`() {
        assertEquals("app.launch", AppLaunchHandler.TYPE)
        assertTrue(AppManagement.DEVICE_OWNER_KEYS.contains(AppManagement.LAUNCH))
    }
}
