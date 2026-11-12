package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskThemeDto
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #192 final review: the exit journal is cleared only once a kiosk is locked and is durable (M1, N2), an exit is
 * bound to the kiosk it was asked for (S5), and leaving the kiosk is handed on (S4).
 */
class KioskApplierCrashSafetyTest {
    private val home = ComponentName("com.mdmesh.agent", "com.mdmesh.agent.KioskHomeAlias")
    private val kioskA = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a"))
    private val kioskB = KioskApplyPayload(mode = "single", pinPackage = "com.b", allowedPackages = listOf("com.b"))

    private fun applier(
        c: FakeController = FakeController(KioskResult.Ok),
        store: KioskStateStore,
        h: FakeHome = FakeHome(),
        j: ExitJournal,
        configuredOn: Boolean,
    ) = KioskApplier(c, store, h, home, journal = j, desiredKioskOn = { configuredOn }) { false }

    /** The process is killed here: nothing after it runs, not even a `finally`, so the test reads the disk at once. */
    private class ProcessDeath : RuntimeException("process death")

    /**
     * Runs [steps] in a process that dies at [ProcessDeath]: returns the journal entry as it is on disk at that
     * instant (what the next process reads), not after the dying process's `finally` blocks.
     */
    private suspend fun journalAtDeath(j: MemJournal, arm: (() -> Unit) -> Unit, steps: suspend () -> Unit):
        ExitJournal.Pending? {
        var onDisk: ExitJournal.Pending? = null
        var died = false
        arm { onDisk = j.saved; died = true; throw ProcessDeath() }
        runCatching { steps() }
        assertTrue("the process died where the test put it", died)
        return onDisk
    }

    /** The next process: a fresh applier on what is on disk; it must lock [saved] with its allowlist and HOME. */
    private suspend fun assertNextStartRelocks(
        onDisk: ExitJournal.Pending?,
        store: KioskStateStore,
        saved: KioskApplyPayload,
        configuredOn: Boolean,
    ) {
        assertNotNull("an entry is left for the next start to settle", onDisk)
        val j = MemJournal().apply { this.saved = onDisk }
        val c = FakeController(KioskResult.Ok); val h = FakeHome()
        assertFalse("never finished as an exit", applier(c, store, h, j, configuredOn).resumeInterruptedExit())
        assertFalse(c.exited)
        assertEquals("allowlist restored", listOf(saved.allowedPackages), c.enters)
        assertEquals("HOME claimed again", "claim:true", h.log.first())
        assertEquals(saved, store.load())
        assertNull("settled", j.saved)
    }

    /** M1: an exit refused partway relocks; a death before the relock locked must not leave the journal empty. */
    @Test fun `a death between a refused exit's relock steps is relocked at the next start`() = runTest {
        for (configuredOn in listOf(false, true)) {
            val j = MemJournal(); val store = InMemoryKioskStateStore(); store.save(kioskA)
            val partial = FakeController(KioskResult.Ok, exitResult = KioskResult.Failed("features"))
            val a = applier(partial, store, j = j, configuredOn = configuredOn)
            val onDisk = journalAtDeath(j, { die -> partial.beforeEnter = { die() } }) { a.exit() }
            assertNextStartRelocks(onDisk, store, kioskA, configuredOn)
        }
    }

    /** M1: the same for a relock a start does (ROLL_BACK and DISCARD): killed again before it locked. */
    @Test fun `a death during a start's relock is relocked at the start after`() = runTest {
        val entries = listOf(
            ExitJournal.Pending(true, true, ExitJournal.fingerprint(kioskA)) to true, // ROLL_BACK: kiosk-on
            ExitJournal.Pending(true, true, ExitJournal.fingerprint(kioskA), attempts = 1) to false, // ROLL_BACK: tried
            ExitJournal.Pending(true, true, ExitJournal.fingerprint(kioskB)) to false, // DISCARD: another kiosk
            ExitJournal.Pending(true, true, "") to false, // DISCARD: unreadable
        )
        for ((entry, configuredOn) in entries) {
            val j = MemJournal().apply { saved = entry }
            val store = InMemoryKioskStateStore(); store.save(kioskA)
            val c = FakeController(KioskResult.Ok)
            val a = applier(c, store, j = j, configuredOn = configuredOn)
            val onDisk = journalAtDeath(j, { die -> c.beforeEnter = { die() } }) { a.resumeInterruptedExit() }
            assertNextStartRelocks(onDisk, store, kioskA, configuredOn)
        }
    }

    /**
     * M1: a new kiosk entered over a cut-off exit of the old one (kiosk.enter before the launcher's resume). A death
     * before the new kiosk is saved leaves the old kiosk saved and its entry: the next start must lock that kiosk
     * again, never finish the old exit (the device was being put in a kiosk, not taken out).
     */
    @Test fun `a death while entering a new kiosk over a cut-off exit never finishes that exit`() = runTest {
        for (dieAt in listOf("lock", "save")) {
            val j = MemJournal(); j.saved = ExitJournal.Pending(true, true, ExitJournal.fingerprint(kioskA))
            val inMemory = InMemoryKioskStateStore(); inMemory.save(kioskA)
            var beforeSave: () -> Unit = {}
            val store = object : KioskStateStore by inMemory {
                override suspend fun save(payload: KioskApplyPayload?) { beforeSave(); inMemory.save(payload) }
            }
            val c = FakeController(KioskResult.Ok)
            val a = applier(c, store, j = j, configuredOn = false)
            val onDisk = journalAtDeath(j, { die ->
                if (dieAt == "lock") c.beforeEnter = { die() } else beforeSave = { die() }
            }) { a.enter(kioskB) }
            assertNextStartRelocks(onDisk, inMemory, kioskA, configuredOn = false)
        }
    }

    /** M1: the clear itself still happens once the kiosk is locked: a completed relock leaves nothing pending. */
    @Test fun `the journal is cleared only after the kiosk is locked`() = runTest {
        val j = MemJournal(); j.saved = ExitJournal.Pending(true, true, ExitJournal.fingerprint(kioskA))
        val store = InMemoryKioskStateStore(); store.save(kioskA)
        val c = FakeController(KioskResult.Ok)
        val entryWhenLocking = mutableListOf<ExitJournal.Pending?>()
        c.beforeEnter = { entryWhenLocking += j.saved }
        applier(c, store, j = j, configuredOn = true).resumeInterruptedExit()
        assertNotNull("entry still on disk while the allowlist is restored", entryWhenLocking.single())
        assertNull(j.saved)
    }

    /** N2: an exit whose entry can't be written durably doesn't start (as PinAttemptStore refuses). */
    @Test fun `an exit whose journal entry can't be committed refuses without a step`() = runTest {
        for (force in listOf(false, true)) {
            val j = object : ExitJournal by MemJournal() { override fun begin(p: ExitJournal.Pending) = false }
            val store = InMemoryKioskStateStore(); store.save(kioskA)
            val c = FakeController(KioskResult.Ok); val h = FakeHome()
            val r = KioskApplier(c, store, h, home, journal = j, desiredKioskOn = { false }) { true }
                .exit(force = force)
            assertTrue(r is KioskResult.Failed)
            assertFalse("no exit step ran", c.exited)
            assertEquals(kioskA, store.load())
            assertTrue(h.log.isEmpty())
        }
    }

    /** N2: a new kiosk isn't entered over an entry that can't be marked superseded (it could be finished later). */
    @Test fun `a new kiosk over a cut-off exit whose entry can't be rewritten is refused`() = runTest {
        val mem = MemJournal(); mem.saved = ExitJournal.Pending(true, true, ExitJournal.fingerprint(kioskA))
        val j = object : ExitJournal by mem { override fun begin(p: ExitJournal.Pending) = false }
        val store = InMemoryKioskStateStore(); store.save(kioskA)
        val c = FakeController(KioskResult.Ok)
        val r = applier(c, store, j = j, configuredOn = false).enter(kioskB)
        assertTrue(r is KioskResult.Failed)
        assertTrue("nothing locked", c.enters.isEmpty())
        assertEquals(kioskA, store.load())
    }

    // --- PR #192 final review (S5): an exit is bound to the kiosk it was asked for ---

    /** S5: a PIN exit verified against kiosk A, queued behind kiosk.enter of B, must not take the device out of B. */
    @Test fun `an exit for a kiosk replaced while it waited for the lock is refused`() = runTest {
        val store = GatedFirstLoadStore(); store.save(kioskA)
        val c = FakeController(KioskResult.Ok); val h = FakeHome(); val j = MemJournal()
        val a = KioskApplier(c, store, h, home, journal = j, desiredKioskOn = { false }) { true }
        launch { a.enter(kioskB) } // holds the lock, parked on its first read
        advanceUntilIdle()
        var exited: KioskResult? = null
        launch { exited = a.exit(handBackHome = true, force = true, expected = kioskA) }
        advanceUntilIdle()
        store.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(KioskResult.Failed(KioskApplier.EXIT_SUPERSEDED), exited)
        assertFalse("no exit step ran", c.exited)
        assertEquals(kioskB, store.load())
        assertFalse("HOME kept", "claim:false" in h.log)
        assertNull("nothing journalled", j.saved)
    }

    /** S5: a new look for the same kiosk (colours, image) is the same kiosk: its PIN exit still goes ahead. */
    @Test fun `an exit for a kiosk only restyled meanwhile goes ahead`() = runTest {
        val store = InMemoryKioskStateStore()
        store.save(kioskA.copy(theme = KioskThemeDto(backgroundColor = "#112233")))
        val c = FakeController(KioskResult.Ok)
        val r = KioskApplier(c, store, FakeHome(), home) { true }.exit(force = true, expected = kioskA)
        assertEquals(KioskResult.Ok, r)
        assertTrue(c.exited)
        assertNull(store.load())
    }

    /** S4: leaving the kiosk stops its image's prefetch; a refused exit (still in kiosk) doesn't. */
    @Test fun `an exit hands on that the kiosk is gone, a refused one does not`() = runTest {
        var exits = 0
        val store = InMemoryKioskStateStore(); store.save(kioskA)
        val refusing = FakeController(KioskResult.Ok, exitResult = KioskResult.Failed("dpm"))
        KioskApplier(refusing, store, FakeHome(), home, onExited = { exits++ }) { true }.exit()
        assertEquals(0, exits)
        KioskApplier(FakeController(KioskResult.Ok), store, FakeHome(), home, onExited = { exits++ }) { true }
            .exit(force = true)
        assertEquals(1, exits)
    }
}
