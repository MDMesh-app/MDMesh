package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.kiosk.KioskToggles
import com.mdmesh.kiosk.lockTaskFeatures
import com.mdmesh.kiosk.safeModeBlocked
import com.mdmesh.proto.KioskApplyPayload
import android.util.Log
import kotlinx.coroutines.sync.Mutex
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
    /** Whether lock task is active now ([KioskController.isLocked]). Last, so a trailing lambda can only be this. */
    private val isLockTaskActive: () -> Boolean,
) {
    private val mutex = Mutex()
    /** Sets Safe Mode per [safeModeBlocked] from [safeMode]; see [KioskSafeMode]. */
    private val safeModeSetter = KioskSafeMode(kiosk, safeMode)

    suspend fun enter(p: KioskApplyPayload): KioskResult = mutex.withLock { enterLocked(p) }

    /** [reassert]: skip the "already in this kiosk" short-circuit (a relock after a cut-off exit). */
    private suspend fun enterLocked(p: KioskApplyPayload, reassert: Boolean = false): KioskResult {
        // Entering supersedes any exit a process death cut off: it must never be finished against this kiosk.
        journal.end()
        // Already in this kiosk: no re-entry or relaunch, but re-assert the volume lock and the Safe Mode block (either
        // may have failed before).
        val current = store.load()
        if (!reassert && current == p && isLockTaskActive()) return applySafeMode(applyVolume(p, KioskResult.Ok))
        home.setClaimEnabled(true)
        return when (val r = lockTo(p)) {
            KioskResult.Ok -> {
                // The device IS in kiosk now, so the state says so even if the volume lock fails below: a failed
                // volume lock is reported (the config document is then not persisted and is re-applied), and the
                // short-circuit above re-asserts it without relaunching the kiosk.
                store.save(p)
                home.showLauncher()
                applySafeMode(applyVolume(p, r))
            }
            else -> {
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
     */
    suspend fun exit(handBackHome: Boolean = true, force: Boolean = false): KioskResult = mutex.withLock {
        exitLocked(handBackHome, force)
    }

    private suspend fun exitLocked(handBackHome: Boolean, force: Boolean): KioskResult {
        journal.begin(ExitJournal.Pending(handBackHome, force, ExitJournal.fingerprint(store.load())))
        val r = kiosk.exit()
        if (r != KioskResult.Ok && !force) {
            journal.end() // refused: nothing changed, nothing to finish later
            return r
        }
        kiosk.setVolumeLocked(false) // best effort: lift the kiosk's volume lock (and its mute)
        store.save(null)
        safeModeSetter.set(inKiosk = false) // best effort, like the volume lock
        if (handBackHome) {
            home.setClaimEnabled(false)
            home.showOemHome()
        }
        journal.end()
        return r
    }

    /**
     * At start (the kiosk launcher, the boot re-apply): settle an exit that began but was cut off (process death
     * between its steps). It is finished, with its original options, only for the same kiosk it was exiting and only
     * while the server's configuration is kiosk-off; with the configuration kiosk-on the saved kiosk is locked again;
     * an entry for another kiosk (one entered since) or one that can't be read is dropped. Fails closed: when unsure,
     * the device stays (or goes back) in kiosk. True when an exit was finished.
     */
    suspend fun resumeInterruptedExit(): Boolean = mutex.withLock {
        val pending = journal.pending()
        val saved = store.load()
        when (ExitJournal.decide(pending, saved, desiredKioskOn())) {
            ExitJournal.Resume.NONE -> false
            ExitJournal.Resume.FINISH -> {
                val p = checkNotNull(pending)
                exitLocked(p.handBackHome, p.force)
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
                    warn("interrupted kiosk exit dropped: no kiosk saved")
                    journal.end()
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
     * Lock [saved] again after a cut-off exit: allowlist, lock task and HOME claim, as a fresh entry (which also
     * clears the journal). A failed relock leaves the device half out of kiosk, so it is logged loudly.
     */
    private suspend fun relock(saved: KioskApplyPayload, why: String) {
        val r = enterLocked(saved, reassert = true)
        if (r == KioskResult.Ok) {
            warn("interrupted kiosk exit not finished ($why): kiosk locked again")
        } else {
            warn("interrupted kiosk exit not finished ($why), and locking the kiosk again failed: $r")
        }
    }

    /** Best effort: logging must not stand in the way (the JVM unit tests have no android.util.Log). */
    private fun warn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private companion object {
        const val TAG = "KioskApplier"
    }

    /** True when a kiosk payload is persisted (device believes it is / should be in kiosk). */
    suspend fun isPersisted(): Boolean = store.load() != null
}
