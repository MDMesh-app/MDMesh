package com.mdmesh.agent.admin

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.work.Configuration
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.mdmesh.core.sync.KioskGuardWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Lock task entered or left, by whatever path: the kiosk guard is reconciled off the receiver (the backstop where
 * every path meets); the receiver itself changes no restriction any more (the guard owns them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdminReceiverLockTaskTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dpm = app.getSystemService(DevicePolicyManager::class.java)

    @Before fun setUp() {
        // A worker that does nothing: only the queueing is under test (the real one needs the injected graph).
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters) =
                object : Worker(appContext, params) { override fun doWork() = Result.success() }
        }
        val config = Configuration.Builder().setWorkerFactory(factory).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, config)
        shadowOf(dpm).setDeviceOwner(AdminReceiver.componentName(app))
    }

    /** Runs of the guard's reconcile queued (the test WorkManager may already have run them). */
    private fun queued() = WorkManager.getInstance(app).getWorkInfosForUniqueWork(KioskGuardWorker.NAME).get().size

    @Test fun `entering lock task queues the guard's reconcile`() {
        AdminReceiver().onLockTaskModeEntering(app, Intent(), "com.kiosk")
        assertEquals(1, queued())
        // Robolectric keeps an admin's restrictions in UserManager.
        val users = app.getSystemService(UserManager::class.java)
        assertFalse(users.hasUserRestriction(UserManager.DISALLOW_CREATE_WINDOWS))
    }

    @Test fun `leaving lock task queues it too`() {
        AdminReceiver().onLockTaskModeExiting(app, Intent())
        assertEquals(1, queued())
    }
}
