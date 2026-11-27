package com.mdmesh.core.sync

import android.app.admin.DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.mdmesh.core.kiosk.FakeController
import com.mdmesh.core.kiosk.FakeGuardDevice
import com.mdmesh.core.kiosk.FakeHome
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskGuard
import com.mdmesh.core.kiosk.LoggedRecords
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.KioskResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lock-task callbacks' reconcile settles the guard and never retries with backoff: a retrying run would hold back
 * the run queued behind it (a later lock-task change), leaving the guard on after a kiosk ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KioskGuardWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val d = FakeGuardDevice(apps = mutableMapOf("com.a" to emptyList()))
    private val applier = KioskApplier(
        FakeController(KioskResult.Ok).apply { allowlist = listOf("com.a", "com.mdmesh.agent") },
        InMemoryKioskStateStore(), FakeHome(), ComponentName("com.mdmesh.agent", "b"),
        guard = KioskGuard(d, LoggedRecords(d), "com.mdmesh.agent") { true },
    ) { true }

    private fun worker(attempt: Int) = TestListenableWorkerBuilder<KioskGuardWorker>(context)
        .setRunAttemptCount(attempt)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters) =
                KioskGuardWorker(appContext, params, applier)
        })
        .build()

    @Test fun `a run settles the guard`() {
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(0).doWork() })
        assertEquals(PERMISSION_POLICY_AUTO_GRANT, d.policy)
    }

    @Test fun `a run the device refuses ends, for the next start, apply or lock-task change`() {
        d.ignorePolicy = true
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(0).doWork() })
    }

    @Test fun `a lock-task change while a run is queued queues another run after it`() {
        // An executor that never runs anything: the first run stays queued, as one waiting on the applier's lock.
        val config = Configuration.Builder().setExecutor { }.build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, config, WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        KioskGuardWorker.schedule(context)
        KioskGuardWorker.schedule(context)
        val runs = WorkManager.getInstance(context).getWorkInfosForUniqueWork(KioskGuardWorker.NAME).get()
        assertEquals("the second change isn't dropped", 2, runs.size)
    }
}
