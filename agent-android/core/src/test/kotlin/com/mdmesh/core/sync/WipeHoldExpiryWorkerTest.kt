package com.mdmesh.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.mdmesh.policy.factoryreset.FactoryResetWipeHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.UUID

/** PR #227 review: the scheduled check ends a wipe hold with no command, apply or server, on real WorkManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WipeHoldExpiryWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager by lazy { WorkManager.getInstance(context) }

    @Volatile private var nowMs = 0L
    @Volatile private var expired = 0
    private val hold = FactoryResetWipeHold(nowMs = { nowMs }) { expired++ }

    /** ConfigReapplyWorker runs, as a stand-in (the real one needs the whole configuration graph). */
    private val reapplies: MutableList<UUID> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker? =
                when (name) {
                    WipeHoldExpiryWorker::class.java.name -> WipeHoldExpiryWorker(context, params, hold)
                    ConfigReapplyWorker::class.java.name -> object : Worker(context, params) {
                        override fun doWork(): Result = Result.success().also { reapplies += id }
                    }
                    else -> null
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(factory).setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun `a hold's check waits for the deadline`() {
        hold.hold()
        WipeHoldExpiryWorker.schedule(context)

        val check = checks().single()
        assertEquals(WorkInfo.State.ENQUEUED, check.state)
        assertEquals(FactoryResetWipeHold.MAX_HOLD_MS, check.initialDelayMillis)
    }

    @Test
    fun `a check that runs early waits again for the time left`() {
        hold.hold()
        WipeHoldExpiryWorker.schedule(context)
        val first = checks().single().id
        nowMs = 4 * 60_000L

        runCheck(first)

        val next = checks().single { it.id != first }
        awaitState(next.id, WorkInfo.State.ENQUEUED)
        assertEquals(FactoryResetWipeHold.MAX_HOLD_MS - nowMs, workInfo(next.id).initialDelayMillis)
        assertEquals(0, expired)
        assertEquals(emptyList<UUID>(), reapplies.toList())
    }

    @Test
    fun `a check at the deadline ends the hold once and re-applies`() {
        hold.hold()
        WipeHoldExpiryWorker.schedule(context)
        nowMs = FactoryResetWipeHold.MAX_HOLD_MS

        runCheck(checks().single().id)

        assertEquals(1, expired)
        awaitReapplies(1)
        assertEquals("no further check", 1, checks().size)
    }

    /** Released (the wipe threw), lost with a process that had no boot count, or ended by a reboot: re-apply anyway. */
    @Test
    fun `a check with no hold re-applies and reports nothing`() {
        WipeHoldExpiryWorker.schedule(context)

        runCheck(checks().single().id)

        assertEquals(0, expired)
        awaitReapplies(1)
    }

    @Test
    fun `a new hold replaces the check an earlier one left`() {
        WipeHoldExpiryWorker.schedule(context)
        val earlier = checks().single().id

        WipeHoldExpiryWorker.schedule(context)

        val now = checks().single() // the earlier one is gone, not left waiting
        assertNotEquals(earlier, now.id)
        assertEquals(WorkInfo.State.ENQUEUED, now.state)
    }

    private fun checks(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(WipeHoldExpiryWorker.UNIQUE_NAME).get()

    private fun workInfo(id: UUID): WorkInfo = requireNotNull(workManager.getWorkInfoById(id).get())

    /** Fires the delay, then waits for the CoroutineWorker, which finishes off the test thread. */
    private fun runCheck(id: UUID) {
        requireNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setInitialDelayMet(id)
        awaitState(id, WorkInfo.State.SUCCEEDED)
    }

    private fun awaitState(id: UUID, state: WorkInfo.State) {
        val until = System.nanoTime() + WAIT_NS
        while (workInfo(id).state != state && System.nanoTime() < until) Thread.sleep(POLL_MS)
        assertEquals(state, workInfo(id).state)
    }

    /** The re-apply is enqueued from the worker's thread, and runs there. */
    private fun awaitReapplies(count: Int) {
        val until = System.nanoTime() + WAIT_NS
        while (reapplies.size < count && System.nanoTime() < until) Thread.sleep(POLL_MS)
        assertEquals(count, reapplies.size)
    }

    private companion object {
        const val WAIT_NS = 5_000_000_000L
        const val POLL_MS = 10L
    }
}
