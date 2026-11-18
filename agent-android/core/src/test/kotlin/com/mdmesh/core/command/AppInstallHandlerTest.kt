package com.mdmesh.core.command

import com.mdmesh.core.command.handlers.AppInstallHandler
import com.mdmesh.core.install.InstallOutcome
import com.mdmesh.core.install.InstallRequest
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** The app's settings are in place before it is started: after-install step first, then the launch. */
class AppInstallHandlerTest {
    private val steps = mutableListOf<String>()

    private fun handler(
        outcome: InstallOutcome = InstallOutcome.Success,
        afterInstall: suspend (String) -> Unit = { steps += "afterInstall:$it" },
    ) = AppInstallHandler(
        install = { req: InstallRequest -> steps += "install:${req.packageName}"; outcome },
        launch = { steps += "launch:$it" },
        afterInstall = afterInstall,
    )

    private fun cmd(runAfterInstall: Boolean) = CommandEnvelope(
        commandId = "1",
        issuedAt = "2026-01-01T00:00:00Z",
        type = "app.install",
        payload = buildJsonObject {
            put("url", "http://lan/app.apk")
            put("packageName", "com.x")
            put("runAfterInstall", runAfterInstall)
        },
    )

    @Test fun `the after-install step runs before the launch`() = runTest {
        val r = handler().handle(cmd(runAfterInstall = true))
        assertEquals(CommandStatus.DONE, r.status)
        assertEquals(listOf("install:com.x", "afterInstall:com.x", "launch:com.x"), steps)
    }

    @Test fun `a failing after-install step neither fails the install nor stops the launch`() = runTest {
        val failing = handler(afterInstall = { steps += "afterInstall"; error("apply failed") })
        val r = failing.handle(cmd(runAfterInstall = true))
        assertEquals(CommandStatus.DONE, r.status)
        assertEquals(listOf("install:com.x", "afterInstall", "launch:com.x"), steps)
    }

    @Test fun `an after-install step with nothing to apply still launches`() = runTest {
        val nothing: suspend (String) -> Any? = { steps += "afterInstall"; null }
        val r = handler(afterInstall = { nothing(it) }).handle(cmd(runAfterInstall = true))
        assertEquals(CommandStatus.DONE, r.status)
        assertEquals(listOf("install:com.x", "afterInstall", "launch:com.x"), steps)
    }

    @Test fun `no launch without runAfterInstall`() = runTest {
        val r = handler().handle(cmd(runAfterInstall = false))
        assertEquals(CommandStatus.DONE, r.status)
        assertEquals(listOf("install:com.x", "afterInstall:com.x"), steps)
    }

    @Test fun `a skipped or failed install neither applies nor launches`() = runTest {
        assertEquals(CommandStatus.DONE, handler(InstallOutcome.Skipped("same version")).handle(cmd(true)).status)
        assertEquals(CommandStatus.FAILED, handler(InstallOutcome.Failure(null, "boom")).handle(cmd(true)).status)
        assertEquals(listOf("install:com.x", "install:com.x"), steps)
    }
}
