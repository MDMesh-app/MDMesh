package com.mdmesh.core.command

import android.content.ComponentName
import com.mdmesh.core.command.handlers.AppInstallHandler
import com.mdmesh.core.kiosk.FakeController
import com.mdmesh.core.kiosk.FakeGuardDevice
import com.mdmesh.core.kiosk.FakeHome
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskGuard
import com.mdmesh.core.kiosk.LoggedRecords
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A kiosk app installed while the device is in kiosk is protected by the install itself, not by a later apply. */
class AppInstallKioskTest {
    @Test fun `the after-install steps protect a kiosk app installed in kiosk`() = runTest {
        val d = FakeGuardDevice(apps = mutableMapOf("com.a" to emptyList()))
        val store = InMemoryKioskStateStore()
        val kiosk = KioskApplier(
            FakeController(KioskResult.Ok), store, FakeHome(), ComponentName("com.mdmesh.agent", "b"),
            guard = KioskGuard(d, LoggedRecords(d), "com.mdmesh.agent") { true },
        ) { true }
        kiosk.enter(KioskApplyPayload(mode = "launcher", allowedPackages = listOf("com.a", "com.new")))
        assertEquals("not installed yet", setOf("com.a"), d.uninstallBlocked)
        d.apps["com.new"] = emptyList()
        val rules = mutableListOf<String>()
        AppInstallHandler.afterInstall({ rules += it }, kiosk)("com.new")
        assertEquals(listOf("com.new"), rules)
        assertTrue("com.new" in d.uninstallBlocked)
    }
}
