package com.mdmesh.core.install

import com.mdmesh.policy.ManagedAppPolicy
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedOperationsTest {

    /** Records which key is lifted while an operation runs. */
    private class FakeManaged(
        override val capabilityKey: String,
        private val log: MutableList<String>,
        private val restoreError: Throwable? = null,
    ) : ManagedAppPolicy {
        var restoreCalls = 0
        override fun isSupported() = true
        override fun setEnabled(enabled: Boolean): PolicyOutcome = PolicyOutcome.Applied
        override suspend fun <T> withAllowed(operation: suspend () -> T): T {
            log += "lift $capabilityKey"
            return try { operation() } finally { log += "restore $capabilityKey" }
        }
        override fun restoreInterrupted(): Boolean {
            restoreCalls++
            restoreError?.let { throw it }
            return true
        }
    }

    private class PlainToggle(override val capabilityKey: String) : TogglePolicy {
        override fun isSupported() = true
        override fun setEnabled(enabled: Boolean): PolicyOutcome = PolicyOutcome.Applied
    }

    private val log = mutableListOf<String>()
    private val install = FakeManaged("userAppInstall", log)
    private val uninstall = FakeManaged("userAppUninstall", log)
    private val ops = ManagedOperations(
        mapOf("userAppInstall" to install, "userAppUninstall" to uninstall, "bluetooth" to PlainToggle("bluetooth")),
    )

    @Test fun `an install lifts only the install restriction`() = runTest {
        assertEquals("ok", ops.install { log += "commit"; "ok" })
        assertEquals(listOf("lift userAppInstall", "commit", "restore userAppInstall"), log)
    }

    @Test fun `an uninstall lifts only the uninstall restriction`() = runTest {
        ops.uninstall { log += "delete" }
        assertEquals(listOf("lift userAppUninstall", "delete", "restore userAppUninstall"), log)
    }

    @Test fun `an unsupported key runs the operation as is`() = runTest {
        val bare = ManagedOperations(mapOf("bluetooth" to PlainToggle("bluetooth")))
        assertEquals(7, bare.install { 7 })
        assertEquals(8, bare.uninstall { 8 })
    }

    @Test fun `operations run one at a time`() = runTest {
        val firstIn = CompletableDeferred<Unit>(); val releaseFirst = CompletableDeferred<Unit>()
        val first = launch { ops.install { log += "install"; firstIn.complete(Unit); releaseFirst.await() } }
        firstIn.await()
        val second = launch { ops.uninstall { log += "uninstall" } }
        testScheduler.runCurrent()
        assertTrue("the uninstall waits for the install", "uninstall" !in log)
        releaseFirst.complete(Unit); first.join(); second.join()
        assertEquals(
            listOf("lift userAppInstall", "install", "restore userAppInstall",
                "lift userAppUninstall", "uninstall", "restore userAppUninstall"),
            log,
        )
    }

    @Test fun `restore reaches every app restriction policy and skips other toggles`() {
        assertTrue(ops.restoreInterrupted().isEmpty())
        assertEquals(1, install.restoreCalls); assertEquals(1, uninstall.restoreCalls)
    }

    @Test fun `one failed restore does not stop the others and is reported`() {
        val failing = FakeManaged("userAppInstall", log, IllegalStateException("dpm"))
        val other = FakeManaged("userAppUninstall", log)
        val failures = ManagedOperations(mapOf("userAppInstall" to failing, "userAppUninstall" to other))
            .restoreInterrupted()
        assertEquals(setOf("userAppInstall"), failures.keys)
        assertEquals("dpm", failures.getValue("userAppInstall").message)
        assertEquals(1, other.restoreCalls)
    }
}
