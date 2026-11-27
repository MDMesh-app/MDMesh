package com.mdmesh.core.command

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.core.command.handlers.AppUninstallHandler
import com.mdmesh.core.install.InstallIntegrityPolicy
import com.mdmesh.core.install.InstallManager
import com.mdmesh.core.install.InstallResultBus
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `app.uninstall` of an app the kiosk guard protects says why, in plain words, instead of an Android error code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUninstallInKioskTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = InstallManager(context, InstallResultBus(), OkHttpClient(), InstallIntegrityPolicy())

    private fun uninstall(pkg: String) = CommandEnvelope(
        commandId = "u1", issuedAt = "2026-01-01T00:00:00Z", type = "app.uninstall",
        payload = buildJsonObject { put("packageName", JsonPrimitive(pkg)) },
    )

    @Test fun `a kiosk app is refused with the reason`() = runTest {
        val r = AppUninstallHandler(manager, inKiosk = { it == "com.kiosk" }).handle(uninstall("com.kiosk"))
        assertEquals(CommandStatus.FAILED, r.status)
        assertEquals(AppUninstallHandler.IN_KIOSK, r.detail)
    }

    @Test fun `another app goes on to the uninstall`() = runTest {
        val r = AppUninstallHandler(manager, inKiosk = { it == "com.kiosk" }).handle(uninstall("com.other"))
        assertEquals(CommandStatus.DONE, r.status)
        assertEquals("skipped: not installed: com.other", r.detail)
    }
}
