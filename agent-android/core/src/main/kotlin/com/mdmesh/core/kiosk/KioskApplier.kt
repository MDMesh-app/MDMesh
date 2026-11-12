package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.kiosk.KioskToggles
import com.mdmesh.kiosk.lockTaskFeatures
import com.mdmesh.kiosk.safeModeBlocked
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.sameKioskAs
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/**
 * The one implementation of "put this device in kiosk with payload P" / "leave kiosk", shared by the
 * `kiosk.enter` / `kiosk.exit` commands and by `config.apply`. Idempotent: entering with the same payload while
 * not locked re-asserts the allowlist and features; exiting when not in kiosk is harmless.
 *
 * Entering with the payload already persisted while lock task is active is a no-op: a re-sent `config.apply`
 * (e.g. after an agent update) must not relaunch the kiosk launcher on a device already showing it; only the
 * volume lock and the Safe Mode block are re-asserted.
 *
 * Safe Mode follows [safeModeBlocked] on every path here: blocked in kiosk unless the configuration allows it, and
 * after kiosk only if the configuration blocks it. [syncSafeMode] is how `config.apply` re-checks it last.
 *
 * Serialized: [enter], [exit], [syncSafeMode] and [releaseStaleVolumeLock] run one at a time, whichever path calls them
 * (`kiosk.enter`/`kiosk.exit`, `config.apply` and its boot re-apply, the launcher's exits, agent start). Otherwise
 * the boot-time release could read "no kiosk", then clear the volume lock a concurrent enter had just set.
 */
// Its collaborators are the kiosk's moving parts (controller, state, HOME, Safe Mode, exit journal, lock state); its
// functions are the serialized kiosk operations. Splitting either would split the one mutex that orders them.
@Suppress("LongParameterList", "TooManyFunctions")
class KioskApplier(
    private val kiosk: KioskController,
    private val store: KioskStateStore,
    private val home: KioskHomeSwitch,
    private val homeComponent: ComponentName,
    /**
     * The configuration's Safe Mode setting, read on every path ([ConfiguredSafeModeSetting] in production). The
     * default is for tests only: an applier built without it would treat an explicit Block as not managed.
     */
    private val safeMode: SafeModeSetting = SafeModeSetting.NotManaged,
    /** Remembers an exit in progress, so one cut off by process death is settled on the next start. */
    private val journal: ExitJournal = ExitJournal.None,
    /**
     * Whether the server's configuration (the desired state) has kiosk on. An interrupted exit is never finished
     * against it. The default is for tests: "on", so a resume fails closed.
     */
    private val desiredKioskOn: () -> Boolean = { true },
    /**
     * Where the journal's disk I/O (and a start's resume) runs: Dispatchers.IO in production (AgentModule), never the
     * main thread. The default runs it in place, for tests that drive the applier on a test scheduler.
     */
    private val io: CoroutineDispatcher = Dispatchers.Unconfined,
    /**
     * Called with each kiosk the device has just been locked into (not a repeat of the kiosk it is in): production
     * queues its background image's download ([KioskBackgroundPrefetchWorker]), so the image is ready before the
     * launcher next shows. Must not block.
     */
    private val onEntered: (KioskApplyPayload) -> Unit = {},
    /** Which boot this is (Settings.Global.BOOT_COUNT): written into a journal entry, for the logs. */
    private val bootId: () -> Long = { 0L },
    /** Whether lock task is active now ([KioskController.isLocked]). Last, so a trailing lambda can only be this. */
    private val isLockTaskActive: () -> Boolean,
) {
    private val mutex = Mutex()
    /** Sets Safe Mode per [safeModeBlocked] from [safeMode]; see [KioskSafeMode]. */
    private val safeModeSetter = KioskSafeMode(kiosk, safeMode)

    suspend fun enter(p: KioskApplyPayload): KioskResult = mutex.withLock { enterLocked(p) }

    /** [reassert]: skip the "already in this kiosk" short-circuit (a relock after a cut-off exit). */
    private suspend fun enterLocked(p: KioskApplyPayload, reassert: Boolean = false): KioskResult {
        // Already in this kiosk: no re-entry or relaunch, but re-assert the volume lock and the Safe Mode block (either
        // may have failed before).
        val current = store.load()
        val pending = withContext(io) { journal.pending() }
        if (!reassert && p.sameKioskAs(current) && isLockTaskActive()) {
            // Locked in this very kiosk: an exit cut off before it is settled in kiosk already. Only an entry that is
            // there is cleared: no disk write on every entry.
            if (pending != null) clearJournal("re-entry of the kiosk it was exiting")
            // The same kiosk, perhaps with a new look (colours, image, fit, focal point, or the image at a new URL):
            // saved in place, the launcher redraws it; nothing is entered or relaunched.
            if (current != p || current?.theme?.backgroundImage?.url != p.theme.backgroundImage?.url) {
                store.save(p)
                onEntered(p)
            }
            return applySafeMode(applyVolume(p, KioskResult.Ok))
        }
        // Entering supersedes an exit a process death cut off, but the entry is cleared only once this kiosk is
        // locked and saved: until then a death must leave something for the next start to settle. So it is first
        // marked "settle in kiosk" (never finish: the device is being put in a kiosk, not taken out), which relocks
        // whichever kiosk is saved when the process died. A mark that can't be made durable refuses a new kiosk (the
        // old entry could otherwise be finished as an exit); a relock goes on regardless, locking is fail-closed.
        if (pending != null && !pending.failed && !withContext(io) { journal.begin(pending.copy(failed = true)) }) {
            warn("could not mark a cut-off kiosk exit as superseded")
            if (!reassert) return KioskResult.Failed("kiosk exit journal not writable")
        }
        home.setClaimEnabled(true)
        return when (val r = lockTo(p)) {
            KioskResult.Ok -> {
                // The device IS in kiosk now, so the state says so even if the volume lock fails below: a failed
                // volume lock is reported (the config document is then not persisted and is re-applied), and the
                // short-circuit above re-asserts it without relaunching the kiosk.
                store.save(p)
                // Locked and saved: nothing is left to settle. Not before: see the mark above.
                if (pending != null) clearJournal("kiosk locked")
                home.showLauncher()
                onEntered(p)
                applySafeMode(applyVolume(p, r))
            }
            else -> {
                // A marked entry, if any, is kept: the next start settles it in kiosk (or, with no kiosk saved, drops
                // it and releases HOME, as below).
                if (current == null) {
                    home.setClaimEnabled(false) // never leave a non-kiosk device claiming HOME
                } else {
                    // A device in kiosk stays in it: the failed entry may already have replaced the allowlist, so
                    // put back the kiosk it had, and keep HOME, or a restart would boot to the phone's own launcher.
                    lockTo(current)
                    applySafeMode(r) // its result is r again, already Failed: the device keeps its kiosk and the block
                }
                r
            }
        }
    }

    private fun lockTo(p: KioskApplyPayload): KioskResult {
        val features = lockTaskFeatures(
            KioskToggles(
                home = p.features.home, recents = p.features.recents, notifications = p.features.notifications,
                systemInfo = p.features.systemInfo, keyguard = p.features.keyguard, lockButtons = p.features.lockButtons,
            ),
        )
        val allowed = (p.allowedPackages + listOfNotNull(p.pinPackage)).distinct()
        return kiosk.enter(homeComponent, allowed, features)
    }

    /** In kiosk: block Safe Mode unless the configuration allows it ([safeModeBlocked]). */
    private fun applySafeMode(entered: KioskResult): KioskResult =
        when (val s = safeModeSetter.set(inKiosk = true)) {
            is KioskResult.Failed -> s
            else -> entered
        }

    /**
     * Set Safe Mode to what [safeModeBlocked] says for the kiosk saved now and the configuration's setting.
     * `config.apply` calls it after its policies and kiosk (including the boot re-apply), so a key that stops being
     * managed, or a kiosk that ends, lifts a block nothing asks for any more. The setting comes from [safeMode], the
     * same source `kiosk.enter` and `kiosk.exit` read: one answer, whichever path asks.
     */
    suspend fun syncSafeMode(): KioskResult = mutex.withLock {
        safeModeSetter.set(inKiosk = store.load() != null)
    }

    /** Volume is its own opt-in (it mutes the device); anything else releases a lock set earlier. */
    private fun applyVolume(p: KioskApplyPayload, entered: KioskResult): KioskResult =
        when (val v = kiosk.setVolumeLocked(p.features.lockVolume == true)) {
            is KioskResult.Failed -> v
            else -> entered
        }

    /**
     * THE kiosk exit. Every path goes through here (`kiosk.exit`, `config.apply` kiosk-off and the on-device
     * admin-PIN exit), so the volume lock, which survives reboots and mutes the device, is always released with it,
     * and the kiosk's Safe Mode block is lifted (unless the configuration itself blocks Safe Mode).
     * A crash loop never exits: the launcher holds in kiosk instead (KioskLauncherActivity).
     *
     * @param handBackHome release the HOME claim and show the OEM launcher (false: the agent stays HOME).
     * @param force clear local state and the volume lock even when the controller could not exit (on-device exits:
     *   the user must get out).
     * @param expected the kiosk this exit was authorized for (the on-device PIN exit: the kiosk its PIN was checked
     *   against). Checked inside the lock, so an exit that waited behind the entry of another kiosk (a `kiosk.enter`
     *   or `config.apply` landing as the PIN was submitted) is refused with [EXIT_SUPERSEDED] and leaves that kiosk
     *   untouched. A new look for the same kiosk ([sameKioskAs]) doesn't change it. Null: exit whatever kiosk is
     *   saved (`kiosk.exit`, `config.apply` kiosk-off).
     * @param beforeSteps run inside the lock, in the caller's context, once the exit is going ahead and before its
     *   first step (the launcher leaves lock task here, so the OEM home can come up); never for a refused exit.
     */
    suspend fun exit(
        handBackHome: Boolean = true,
        force: Boolean = false,
        expected: KioskApplyPayload? = null,
        beforeSteps: () -> Unit = {},
    ): KioskResult = mutex.withLock {
        if (expected != null && !expected.sameKioskAs(store.load())) {
            warn("kiosk exit refused: the kiosk it was asked for has been replaced")
            return@withLock KioskResult.Failed(EXIT_SUPERSEDED)
        }
        beforeSteps()
        exitLocked(handBackHome, force)
    }

    private suspend fun exitLocked(
        handBackHome: Boolean,
        force: Boolean,
        /** A start finishing a cut-off exit: its entry, its attempt counted. */
        resumed: ExitJournal.Pending? = null,
    ): KioskResult {
        val entry = resumed
            ?: ExitJournal.Pending(handBackHome, force, ExitJournal.fingerprint(store.load()), boot = bootId())
        // No durable entry, no exit: a death between its steps would leave a half-exited kiosk nothing settles (the
        // same rule as PinAttemptStore). Nothing has been touched yet, so the device simply stays in kiosk.
        if (!withContext(io) { journal.begin(entry) }) {
            warn("kiosk exit not started: its journal entry could not be written")
            return KioskResult.Failed("kiosk exit journal not writable")
        }
        var completed = false
        try {
            return exitSteps(handBackHome, force).also { completed = true }
        } finally {
            // A step threw with the agent running (not a process death): the entry stays, marked, and the next start
            // settles it in kiosk instead of finishing it later.
            if (!completed && !withContext(NonCancellable + io) { journal.begin(entry.copy(failed = true)) }) {
                warn("kiosk exit step threw and its entry could not be marked; the next start settles it as written")
            }
        }
    }

    private suspend fun exitSteps(handBackHome: Boolean, force: Boolean): KioskResult {
        val r = kiosk.exit()
        if (r != KioskResult.Ok && !force) {
            // Refused, but maybe partway (the allowlist already dropped when a later step failed): settle in kiosk
            // by locking the saved kiosk again, so the device is in the kiosk its state says.
            val saved = store.load()
            if (saved != null) relock(saved, "exit refused") else clearJournal("exit refused, no kiosk saved")
            return r
        }
        kiosk.setVolumeLocked(false) // best effort: lift the kiosk's volume lock (and its mute)
        store.save(null)
        safeModeSetter.set(inKiosk = false) // best effort, like the volume lock
        if (handBackHome) {
            home.setClaimEnabled(false)
            home.showOemHome()
        }
        clearJournal("exit finished")
        return r
    }

    /**
     * At start (the kiosk launcher, the boot re-apply): settle an exit that began but was cut off (process death
     * between its steps). It is finished, with its original options, only for the same kiosk it was exiting and only
     * while the server's configuration is kiosk-off; with the configuration kiosk-on the saved kiosk is locked again;
     * an entry for another kiosk (one entered since) or one that can't be read is dropped. Fails closed: when unsure,
     * the device stays (or goes back) in kiosk. True when an exit was finished.
     */
    suspend fun resumeInterruptedExit(): Boolean = withContext(io) {
        mutex.withLock { resumeLocked() }
    }

    /** Off the main thread ([io]); reads the configuration only when an entry is there. */
    private suspend fun resumeLocked(): Boolean {
        val pending = journal.pending() ?: return false
        val saved = store.load()
        return when (ExitJournal.decide(pending, saved, desiredKioskOn)) {
            ExitJournal.Resume.NONE -> false
            ExitJournal.Resume.FINISH -> {
                // The attempt is counted before the first step: if this start dies or throws too, the next one
                // settles in kiosk rather than trying again.
                exitLocked(pending.handBackHome, pending.force, pending.copy(attempts = pending.attempts + 1))
                true
            }
            ExitJournal.Resume.ROLL_BACK -> {
                relock(checkNotNull(saved), "configuration still sets a kiosk")
                false
            }
            ExitJournal.Resume.DISCARD -> {
                if (saved != null) {
                    relock(saved, "entry not for the kiosk saved now, or unreadable")
                } else {
                    // The state is already cleared: release HOME too, as a finished exit would (no kiosk to keep),
                    // unless the configuration sets a kiosk, whose re-apply then claims it again anyway.
                    if (!desiredKioskOn()) home.setClaimEnabled(false)
                    warn("interrupted kiosk exit dropped: no kiosk saved")
                    clearJournal("no kiosk saved")
                }
                false
            }
        }
    }

    /** At agent start: drop a volume lock left behind (e.g. by an interrupted exit) unless the kiosk wants it. */
    suspend fun releaseStaleVolumeLock(): Unit = mutex.withLock {
        if (store.load()?.features?.lockVolume != true) kiosk.setVolumeLocked(false)
    }

    /**
     * Lock [saved] again after a cut-off exit: allowlist, lock task and HOME claim, as a fresh entry (which clears the
     * journal once the kiosk is locked, never before). A failed relock leaves the device half out of kiosk, so it is
     * logged loudly.
     */
    private suspend fun relock(saved: KioskApplyPayload, why: String) {
        val r = enterLocked(saved, reassert = true)
        if (r == KioskResult.Ok) {
            warn("interrupted kiosk exit not finished ($why): kiosk locked again")
        } else {
            // The device may be half out of kiosk: keep a (failed) entry so the next start tries the relock again.
            val retry = ExitJournal.Pending(true, false, ExitJournal.fingerprint(saved), boot = bootId(), failed = true)
            if (!withContext(io) { journal.begin(retry) }) warn("could not keep the failed relock's journal entry")
            warn("kiosk exit not finished ($why), and locking the kiosk again failed: $r; tried again at next start")
        }
    }

    /**
     * Clears the journal, last: every caller has already done what the entry stood for. A clear that doesn't reach the
     * disk leaves the entry, which the next start settles in kiosk (fail closed), so it is only logged.
     */
    private suspend fun clearJournal(why: String) {
        if (!withContext(io) { journal.end() }) warn("kiosk exit journal not cleared ($why); the next start settles it")
    }

    /** Best effort: logging must not stand in the way (the JVM unit tests have no android.util.Log). */
    private fun warn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    companion object {
        private const val TAG = "KioskApplier"

        /** [exit]'s reason when the kiosk it was asked for was replaced before it ran: nothing was changed. */
        const val EXIT_SUPERSEDED = "kiosk replaced before the exit ran"
    }

    /** True when a kiosk payload is persisted (device believes it is / should be in kiosk). */
    suspend fun isPersisted(): Boolean = store.load() != null
}
