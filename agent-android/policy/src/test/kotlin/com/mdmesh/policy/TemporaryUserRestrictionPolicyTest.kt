package com.mdmesh.policy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TemporaryUserRestrictionPolicyTest {
    private class Backend(var blocked: Boolean = true) {
        var supported = true
        var rejectClear = false
        var rejectRestore = false
        var ignoreWrites = false
        var failAfterClear = false
        val writes = mutableListOf<Boolean>()
        fun write(value: Boolean) {
            writes += value
            if (!value && rejectClear) error("clear rejected")
            if (value && rejectRestore) error("restore rejected")
            if (!ignoreWrites) blocked = value
            if (!value && failAfterClear) error("clear changed OS then failed")
        }
        fun policy() = TemporaryUserRestrictionPolicy("userAppInstall", { supported }, { blocked }, ::write)
    }

    @Test fun `explicit toggles use allowed polarity`() {
        val b = Backend(false); val p = b.policy()
        assertEquals(PolicyOutcome.Applied, p.setEnabled(false)); assertTrue(b.blocked)
        assertEquals(PolicyOutcome.Applied, p.setEnabled(true)); assertFalse(b.blocked)
    }
    @Test fun `unsupported policy never mutates the platform`() {
        val b = Backend(); b.supported = false
        assertEquals(PolicyOutcome.Unsupported, b.policy().setEnabled(false)); assertTrue(b.writes.isEmpty())
    }
    @Test fun `policy errors are not reported applied`() {
        val b = Backend(false); b.rejectRestore = true
        assertTrue(b.policy().setEnabled(false) is PolicyOutcome.Failed)
    }
    @Test fun `managed operation restores original block`() = runBlocking {
        val b = Backend()
        assertEquals("installed", b.policy().withAllowed { assertFalse(b.blocked); "installed" })
        assertTrue(b.blocked); assertEquals(listOf(false, true), b.writes)
    }
    @Test fun `originally allowed device remains allowed`() = runBlocking {
        val b = Backend(false); b.policy().withAllowed { assertFalse(b.blocked) }; assertFalse(b.blocked)
    }
    @Test fun `only the relevant restriction is lifted`() = runBlocking {
        val install = Backend(); val uninstall = Backend()
        install.policy().withAllowed { assertFalse(install.blocked); assertTrue(uninstall.blocked) }
        assertTrue(install.blocked); assertTrue(uninstall.blocked)
    }
    @Test fun `failed clear prevents operation and rolls back`() = runBlocking {
        val b = Backend(); b.rejectClear = true; var ran = false
        val e = runCatching { b.policy().withAllowed { ran = true } }.exceptionOrNull()
        assertEquals("clear rejected", e?.message); assertFalse(ran); assertTrue(b.blocked)
    }
    @Test fun `partially failed clear is rolled back`() = runBlocking {
        val b = Backend(); b.failAfterClear = true
        assertNotNull(runCatching { b.policy().withAllowed { fail("must not run") } }.exceptionOrNull())
        assertTrue(b.blocked)
    }
    @Test fun `nonconverging clear prevents operation`() = runBlocking {
        val b = Backend(); b.ignoreWrites = true; var ran = false
        assertNotNull(runCatching { b.policy().withAllowed { ran = true } }.exceptionOrNull())
        assertFalse(ran); assertTrue(b.blocked)
    }
    @Test fun `operation failure propagates after restoration`() = runBlocking {
        val b = Backend(); val original = IllegalStateException("installer failed")
        val e = runCatching { b.policy().withAllowed { throw original } }.exceptionOrNull()
        assertSame(original, e); assertTrue(b.blocked)
    }
    @Test fun `restoration failure cannot turn into success`() = runBlocking {
        val b = Backend(); b.rejectRestore = true
        val e = runCatching { b.policy().withAllowed { "success" } }.exceptionOrNull()
        assertEquals("restore rejected", e?.message); assertFalse(b.blocked)
    }
    @Test fun `restoration failure is retained with operation failure`() = runBlocking {
        val b = Backend(); b.rejectRestore = true; val original = IllegalStateException("installer failed")
        val e = runCatching { b.policy().withAllowed { throw original } }.exceptionOrNull()
        assertSame(original, e); assertEquals("restore rejected", e?.suppressed?.single()?.message)
    }
    @Test fun `cancellation restores before coroutine finishes`() = runBlocking {
        val b = Backend(); val entered = CompletableDeferred<Unit>()
        val job = launch { b.policy().withAllowed { entered.complete(Unit); awaitCancellation() } }
        entered.await(); assertFalse(b.blocked); job.cancel(); job.join(); assertTrue(b.blocked)
    }
    @Test fun `cancellation remains cancellation when restoration fails`() = runBlocking {
        val b = Backend(); b.rejectRestore = true; val original = CancellationException("cancelled")
        val e = runCatching { b.policy().withAllowed { throw original } }.exceptionOrNull()
        assertSame(original, e); assertEquals(1, e?.suppressed?.size)
    }
    @Test fun `policy changes fail for retry during exception`() = runBlocking {
        val b = Backend(); val p = b.policy()
        p.withAllowed {
            assertTrue(p.setEnabled(true) is PolicyOutcome.Failed)
            assertTrue(p.setEnabled(false) is PolicyOutcome.Failed); assertFalse(b.blocked)
        }
        assertTrue(b.blocked)
        assertEquals(PolicyOutcome.Applied, p.setEnabled(true)); assertFalse(b.blocked)
    }
    @Test fun `overlapping exceptions restore only after last operation`() = runBlocking {
        val b = Backend(); val p = b.policy()
        val firstEntered = CompletableDeferred<Unit>(); val secondEntered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>(); val finishSecond = CompletableDeferred<Unit>()
        val first = launch { p.withAllowed { firstEntered.complete(Unit); finishFirst.await() } }
        firstEntered.await()
        val second = launch { p.withAllowed { secondEntered.complete(Unit); finishSecond.await() } }
        secondEntered.await(); finishFirst.complete(Unit); first.join(); assertFalse(b.blocked)
        finishSecond.complete(Unit); second.join(); assertTrue(b.blocked)
        assertEquals(listOf(false, true), b.writes)
    }
    @Test fun `missing callback keeps exception open until cancellation`() = runBlocking {
        val b = Backend(); val entered = CompletableDeferred<Unit>(); val result = CompletableDeferred<Unit>()
        val job = launch { b.policy().withAllowed { entered.complete(Unit); result.await() } }
        entered.await(); assertTrue(job.isActive); assertFalse(b.blocked)
        job.cancel(); job.join(); assertTrue(b.blocked)
    }
}
