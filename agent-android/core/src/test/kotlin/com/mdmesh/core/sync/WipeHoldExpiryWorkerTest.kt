package com.mdmesh.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.reportedRevision
import com.mdmesh.policy.factoryreset.FactoryResetWipeHold
import com.mdmesh.proto.ConfigApplyPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** PR #227 review: the scheduled check ends a wipe hold with no command, apply or server, on real WorkManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WipeHoldExpiryWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager by lazy { WorkManager.getInstance(context) }

    /** The configuration a config.apply saved during the hold, with the block deferred. */
    private val config = InMemoryConfigStateStore().apply { save(ConfigApplyPayload(revision = "r1")) }

    @Volatile private var nowMs = 0L
    @Volatile private var expired = 0

    /** As AgentModule builds it: the expiry reports `wipeNotStarted` (counted here) and runs holdEnded. */
    private val hold = FactoryResetWipeHold(nowMs = { nowMs }) {
        expired++
        WipeHoldExpiryWorker.holdEnded(context, config)
    }

    /** ConfigReapplyWorker runs, as a stand-in (the real one needs the whole configuration graph). */
    private val reapplies: MutableList<UUID> = Collections.synchronizedList(mutableListOf())

    /**
     * With [inFlightReapply], the stand-in re-apply sets Factory reset: Block as the real one does (deferred while the
     * hold is on) under a lock, as ConfigApplier's, and its first run holds that lock until [gate] opens; then it ends
     * failed when [firstFails].
     */
    @Volatile private var inFlightReapply = false
    @Volatile private var firstFails = false
    private val gate = CompletableDeferred<Unit>()
    private val applyLock = Mutex()
    private val started = AtomicInteger()
    @Volatile private var deferredRuns = 0
    @Volatile private var blocked = false

    @Before
    fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker? =
                when (name) {
                    WipeHoldExpiryWorker::class.java.name -> WipeHoldExpiryWorker(context, params, hold, config)
                    ConfigReapplyWorker::class.java.name -> if (inFlightReapply) {
                        object : CoroutineWorker(context, params) {
                            override suspend fun doWork(): Result {
                                val first = started.getAndIncrement() == 0
                                applyLock.withLock {
                                    blocked = !hold.held
                                    if (!blocked) deferredRuns++
                                    if (first) gate.await()
                                }
                                reapplies += id
                                return if (first && firstFails) Result.failure() else Result.success()
                            }
                        }
                    } else {
                        object : Worker(context, params) {
                            override fun doWork(): Result = Result.success().also { reapplies += id }
                        }
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
        // Nothing enqueued, so nothing can still be on its way.
        assertEquals(emptyList<WorkInfo>(), reapplyWork(ConfigReapplyWorker.UNIQUE_NAME))
        assertEquals(emptyList<WorkInfo>(), reapplyWork(ConfigReapplyWorker.AFTER_HOLD_NAME))
        assertEquals("r1", config.revision())
    }

    @Test
    fun `a check at the deadline ends the hold once, re-applies once and has the server re-send`() {
        hold.hold()
        WipeHoldExpiryWorker.schedule(context)
        nowMs = FactoryResetWipeHold.MAX_HOLD_MS

        runCheck(checks().single().id)

        assertEquals(1, expired)
        awaitReapplies(1)
        assertEquals("one re-apply, not one from the expiry and one from the check", 1, afterHold().size)
        assertEquals("no further check", 1, checks().size)
        assertEquals(ConfigStateStore.STALE_REVISION, config.reportedRevision())
    }

    /** Released (the wipe threw), lost with a process that had no boot count, or ended by a reboot: re-apply anyway. */
    @Test
    fun `a check with no hold re-applies and reports nothing`() {
        WipeHoldExpiryWorker.schedule(context)

        runCheck(checks().single().id)

        assertEquals(0, expired)
        awaitReapplies(1)
        assertEquals(1, afterHold().size)
        assertEquals(ConfigStateStore.STALE_REVISION, config.reportedRevision())
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

    /**
     * PR #227 review: a re-apply is running and deferred the block (the hold was on) when the hold ends. Its re-apply
     * must still run after it, or the block stays off until the server re-sends; a KEEP enqueue was dropped.
     */
    @Test
    fun `a check at the deadline re-applies after a re-apply already running`() {
        inFlightReapply = true
        hold.hold()
        WipeHoldExpiryWorker.schedule(context)
        ConfigReapplyWorker.scheduleNow(context) // the boot or an earlier expiry's re-apply
        awaitDeferred(1)

        nowMs = FactoryResetWipeHold.MAX_HOLD_MS
        runCheck(checks().single().id)
        gate.complete(Unit) // the running re-apply finishes; it deferred the block

        awaitReapplies(2)
        assertTrue("the block is back", blocked)
    }

    /** The same race when a command or a check-in, not the check, is the first to read the expired hold. */
    @Test
    fun `an expiry seen elsewhere re-applies after a re-apply already running`() {
        inFlightReapply = true
        hold.hold()
        ConfigReapplyWorker.scheduleNow(context)
        awaitDeferred(1)

        nowMs = FactoryResetWipeHold.MAX_HOLD_MS
        hold.expireIfDue()
        gate.complete(Unit)

        awaitReapplies(2)
        assertTrue("the block is back", blocked)
    }

    /** PR #227 review: a running boot re-apply that ends failed doesn't take the expiry's re-apply down with it. */
    @Test
    fun `the expiry re-apply runs even when the re-apply running before it fails`() {
        inFlightReapply = true
        firstFails = true
        hold.hold()
        ConfigReapplyWorker.scheduleNow(context)
        awaitDeferred(1)

        nowMs = FactoryResetWipeHold.MAX_HOLD_MS
        hold.expireIfDue()
        gate.complete(Unit)

        awaitReapplies(2)
        awaitState(reapplyWork(ConfigReapplyWorker.UNIQUE_NAME).single().id, WorkInfo.State.FAILED)
        awaitState(afterHold().single().id, WorkInfo.State.SUCCEEDED)
        assertTrue("the block is back", blocked)
    }

    private fun checks(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(WipeHoldExpiryWorker.UNIQUE_NAME).get()

    private fun reapplyWork(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun afterHold(): List<WorkInfo> = reapplyWork(ConfigReapplyWorker.AFTER_HOLD_NAME)

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

    private fun awaitDeferred(count: Int) {
        val until = System.nanoTime() + WAIT_NS
        while (deferredRuns < count && System.nanoTime() < until) Thread.sleep(POLL_MS)
        assertEquals(count, deferredRuns)
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
