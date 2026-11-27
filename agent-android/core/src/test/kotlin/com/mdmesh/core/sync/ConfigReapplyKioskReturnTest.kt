package com.mdmesh.core.sync

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.core.kiosk.FakeController
import com.mdmesh.core.kiosk.FakeGuardDevice
import com.mdmesh.core.kiosk.FakeHome
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskGuard
import com.mdmesh.core.kiosk.LoggedRecords
import com.mdmesh.core.store.AgentDataStores
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.core.testing.fixedToggles
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

/** The start-time re-apply (boot, agent update) arms the guard and brings back a kiosk lock task left. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfigReapplyKioskReturnTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Any EventSink shape the agent has had (this test is backported too). */
    private val events = Proxy.newProxyInstance(EventSink::class.java.classLoader, arrayOf(EventSink::class.java)) {
            _, method, _ ->
        if (method.name == "drain") emptyList<Any>() else Unit
    } as EventSink

    @Test fun `a kiosk saved with lock task off is armed and shown again`() {
        val d = FakeGuardDevice(apps = mutableMapOf("com.a" to emptyList()))
        val home = FakeHome()
        val store = InMemoryKioskStateStore()
        val saved = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a"))
        runBlocking { store.save(saved) }
        val kiosk = KioskApplier(
            FakeController(KioskResult.Ok), store, home, ComponentName("com.mdmesh.agent", "b"),
            guard = KioskGuard(d, LoggedRecords(d), "com.mdmesh.agent") { false },
        ) { false } // the update ended lock task
        val config = InMemoryConfigStateStore()
        val applier = ConfigApplier(fixedToggles(), kiosk, {}, config, setPowerMode = {})
        val stores = AgentDataStores({ File(tmp.root, "$it.preferences_pb") }, config, events)
        val worker = TestListenableWorkerBuilder<ConfigReapplyWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters) =
                    ConfigReapplyWorker(appContext, params, applier, kiosk, stores, events)
            })
            .build()
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        assertTrue("armed", kiosk.protectsApp("com.a"))
        assertEquals(listOf("claim:true", "cleared:com.a", "launcher"), home.log)
    }
}
