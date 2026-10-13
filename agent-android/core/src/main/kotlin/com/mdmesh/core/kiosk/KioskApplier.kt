package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.kiosk.KioskToggles
import com.mdmesh.kiosk.lockTaskFeatures
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one implementation of "put this device in kiosk with payload P" / "leave kiosk", shared by the
 * `kiosk.enter` / `kiosk.exit` commands and by `config.apply`. Idempotent: entering with the same payload while
 * not locked re-asserts the allowlist and features; exiting when not in kiosk is harmless.
 *
 * Entering with the payload already persisted while lock task is active is a no-op: a re-sent `config.apply`
 * (e.g. after an agent update) must not relaunch the kiosk launcher on a device already showing it; only the
 * volume lock is re-asserted.
 *
 * Serialized: [enter], [exit] and [releaseStaleVolumeLock] run one at a time, whichever path calls them
 * (`kiosk.enter`/`kiosk.exit`, `config.apply` and its boot re-apply, the launcher's exits, agent start). Otherwise
 * the boot-time release could read "no kiosk", then clear the volume lock a concurrent enter had just set.
 */
class KioskApplier(
    private val kiosk: KioskController,
    private val store: KioskStateStore,
    private val home: KioskHomeSwitch,
    private val homeComponent: ComponentName,
    /** Whether lock task is active now ([KioskController.isLocked]). */
    private val isLockTaskActive: () -> Boolean,
) {
    private val mutex = Mutex()

    suspend fun enter(p: KioskApplyPayload): KioskResult = mutex.withLock { enterLocked(p) }

    private suspend fun enterLocked(p: KioskApplyPayload): KioskResult {
        // Already in this kiosk: no re-entry or relaunch, but re-assert the volume lock (it may have failed before).
        if (store.load() == p && isLockTaskActive()) return applyVolume(p, KioskResult.Ok)
        val features = lockTaskFeatures(
            KioskToggles(
                home = p.features.home, recents = p.features.recents, notifications = p.features.notifications,
                systemInfo = p.features.systemInfo, keyguard = p.features.keyguard, lockButtons = p.features.lockButtons,
            ),
        )
        val allowed = (p.allowedPackages + listOfNotNull(p.pinPackage)).distinct()
        home.setClaimEnabled(true)
        return when (val r = kiosk.enter(homeComponent, allowed, features)) {
            KioskResult.Ok -> {
                // The device IS in kiosk now, so the state says so even if the volume lock fails below: a failed
                // volume lock is reported (the config document is then not persisted and is re-applied), and the
                // short-circuit above re-asserts it without relaunching the kiosk.
                store.save(p)
                home.showLauncher()
                applyVolume(p, r)
            }
            else -> { home.setClaimEnabled(false); r } // never leave a non-kiosk device claiming HOME
        }
    }

    /** Volume is its own opt-in (it mutes the device); anything else releases a lock set earlier. */
    private fun applyVolume(p: KioskApplyPayload, entered: KioskResult): KioskResult =
        when (val v = kiosk.setVolumeLocked(p.features.lockVolume == true)) {
            is KioskResult.Failed -> v
            else -> entered
        }

    /**
     * THE kiosk exit. Every path goes through here (`kiosk.exit`, `config.apply` kiosk-off, the on-device admin-PIN
     * exit and the crash-loop bail-out), so the volume lock, which survives reboots and mutes the device, is always
     * released with it.
     *
     * @param handBackHome release the HOME claim and show the OEM launcher (false: the agent stays HOME, e.g. to
     *   show its recovery screen after a crash loop).
     * @param force clear local state and the volume lock even when the controller could not exit (on-device exits:
     *   the user must get out).
     */
    suspend fun exit(handBackHome: Boolean = true, force: Boolean = false): KioskResult = mutex.withLock {
        exitLocked(handBackHome, force)
    }

    private suspend fun exitLocked(handBackHome: Boolean, force: Boolean): KioskResult {
        val r = kiosk.exit()
        if (r != KioskResult.Ok && !force) return r
        kiosk.setVolumeLocked(false) // best effort: lift the kiosk's volume lock (and its mute)
        store.save(null)
        if (handBackHome) {
            home.setClaimEnabled(false)
            home.showOemHome()
        }
        return r
    }

    /** At agent start: drop a volume lock left behind (e.g. by an interrupted exit) unless the kiosk wants it. */
    suspend fun releaseStaleVolumeLock(): Unit = mutex.withLock {
        if (store.load()?.features?.lockVolume != true) kiosk.setVolumeLocked(false)
    }

    /** True when a kiosk payload is persisted (device believes it is / should be in kiosk). */
    suspend fun isPersisted(): Boolean = store.load() != null
}
