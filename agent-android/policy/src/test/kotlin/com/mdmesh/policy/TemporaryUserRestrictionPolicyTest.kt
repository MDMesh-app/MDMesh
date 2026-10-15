package com.mdmesh.policy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        var readFails = false
        val writes = mutableListOf<Boolean>()
        fun write(value: Boolean) {
            writes += value
            if (!value && rejectClear) error("clear rejected")
            if (value && rejectRestore) error("restore rejected")
            if (!ignoreWrites) blocked = value
            if (!value && failAfterClear) error("clear changed OS then failed")
        }
        val store = InMemoryLiftRestoreStore()
        fun policy() = TemporaryUserRestrictionPolicy("userAppInstall", { supported }, ::read, ::write, store)
        fun read(): Boolean = if (readFails) error("read failed") else blocked
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

    // --- process death: the pre-lift value is recorded first and put back at the next agent start ---

    @Test fun `the pre-lift value is recorded during the operation and cleared after it`() = runBlocking {
        val b = Backend()
        b.policy().withAllowed { assertEquals(true, b.store.get("userAppInstall")) }
        assertNull(b.store.get("userAppInstall")); assertTrue(b.blocked)
    }
    @Test fun `a lift left by a dead process is restored at the next start`() {
        // The process died mid-install: restriction still lifted, record still there. A fresh policy instance
        // (new process) shares only the persisted store.
        val b = Backend(blocked = false); b.store.put("userAppInstall", true)
        assertTrue(b.policy().restoreInterrupted())
        assertTrue(b.blocked); assertNull(b.store.get("userAppInstall"))
    }
    @Test fun `restore is a no-op with nothing recorded`() {
        val b = Backend(blocked = false)
        assertFalse(b.policy().restoreInterrupted()); assertTrue(b.writes.isEmpty())
    }
    @Test fun `an operation that starts before the restore keeps the recorded value`() = runBlocking {
        val b = Backend(blocked = false); b.store.put("userAppInstall", true)
        b.policy().withAllowed { assertFalse(b.blocked) }
        assertTrue("restores what the dead process lifted, not the lifted state it found", b.blocked)
        assertNull(b.store.get("userAppInstall"))
    }
    @Test fun `restore does not touch a running operation`() = runBlocking {
        val b = Backend(); val p = b.policy()
        p.withAllowed { assertFalse(p.restoreInterrupted()); assertFalse(b.blocked) }
        assertTrue(b.blocked)
    }
    @Test fun `a failed restore keeps the record for the next start`() = runBlocking {
        val b = Backend(); b.rejectRestore = true
        assertNotNull(runCatching { b.policy().withAllowed { "installed" } }.exceptionOrNull())
        assertFalse(b.blocked); assertEquals(true, b.store.get("userAppInstall"))
        b.rejectRestore = false
        assertTrue(b.policy().restoreInterrupted()); assertTrue(b.blocked)
    }
    @Test fun `an explicit policy apply supersedes a stale record`() {
        val b = Backend(blocked = false); b.store.put("userAppInstall", true)
        val p = b.policy()
        assertEquals(PolicyOutcome.Applied, p.setEnabled(true)); assertFalse(b.blocked)
        assertNull(b.store.get("userAppInstall")); assertFalse(p.restoreInterrupted()); assertFalse(b.blocked)
    }
    @Test fun `a rejected clear leaves no record behind`() = runBlocking {
        val b = Backend(); b.rejectClear = true
        assertNotNull(runCatching { b.policy().withAllowed { } }.exceptionOrNull())
        assertTrue(b.blocked); assertNull(b.store.get("userAppInstall"))
    }

    // --- fast path: a device that does not use the block gets no writes, so installs gain no new failure mode ---

    @Test fun `nothing is written when the restriction is not set`() = runBlocking {
        val b = Backend(blocked = false)
        assertEquals("installed", b.policy().withAllowed { "installed" })
        assertTrue(b.writes.isEmpty()); assertNull(b.store.get("userAppInstall")); assertFalse(b.blocked)
    }
    @Test fun `the busy guard still holds on the fast path`() = runBlocking {
        val b = Backend(blocked = false); val p = b.policy()
        p.withAllowed { assertTrue(p.setEnabled(false) is PolicyOutcome.Failed) }
        assertTrue(b.writes.isEmpty())
        assertEquals(PolicyOutcome.Applied, p.setEnabled(false)); assertTrue(b.blocked)
    }
    @Test fun `a device that lost Device Owner runs the operation without lifting`() = runBlocking {
        val b = Backend(); b.supported = false
        assertEquals("installed", b.policy().withAllowed { "installed" })
        assertTrue(b.writes.isEmpty())
    }
    @Test fun `an unreadable state runs the operation as before the policy`() = runBlocking {
        val b = Backend(blocked = false); b.readFails = true
        assertEquals("installed", b.policy().withAllowed { "installed" })
        assertTrue(b.writes.isEmpty()); assertNull(b.store.get("userAppInstall"))
    }
    @Test fun `a block set by a later operation is lifted again`() = runBlocking {
        val b = Backend(blocked = false); val p = b.policy()
        p.withAllowed { }
        assertEquals(PolicyOutcome.Applied, p.setEnabled(false))
        p.withAllowed { assertFalse(b.blocked) }
        assertTrue(b.blocked); assertEquals(listOf(true, false, true), b.writes)
    }

    @Test fun `an unreadable state on a blocked device writes nothing and keeps the block`() = runBlocking {
        val b = Backend(blocked = true); b.readFails = true
        assertEquals("ran", b.policy().withAllowed { "ran" })
        assertTrue(b.writes.isEmpty()); assertTrue(b.blocked); assertNull(b.store.get("userAppInstall"))
    }
    @Test fun `a record that cannot be cleared makes an explicit apply fail for re-send`() {
        val b = Backend(blocked = true)
        val store = object : LiftRestoreStore by b.store {
            override fun remove(key: String) = error("disk full")
        }
        store.put("userAppInstall", true)
        val p = TemporaryUserRestrictionPolicy("userAppInstall", { true }, b::read, b::write, store)
        assertTrue(p.setEnabled(true) is PolicyOutcome.Failed)
    }
    @Test fun `a record that cannot be cleared does not fail a finished install`() = runBlocking {
        val b = Backend(blocked = true)
        val store = object : LiftRestoreStore by b.store {
            override fun remove(key: String) = error("disk full")
        }
        val p = TemporaryUserRestrictionPolicy("userAppInstall", { true }, b::read, b::write, store)
        assertEquals("installed", p.withAllowed { "installed" })
        // The leftover record equals the device state, so the next restore rewrites the same value.
        assertTrue(b.blocked); assertEquals(true, store.get("userAppInstall"))
    }
}
