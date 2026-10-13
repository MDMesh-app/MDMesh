package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.kiosk.KioskToggles
import com.mdmesh.kiosk.lockTaskFeatures
import com.mdmesh.proto.KioskApplyPayload

/**
 * The one implementation of "put this device in kiosk with payload P" / "leave kiosk", shared by the
 * `kiosk.enter` / `kiosk.exit` commands and by `config.apply`. Idempotent: entering with the same payload while
 * not locked re-asserts the allowlist and features; exiting when not in kiosk is harmless.
 *
 * Entering with the payload already persisted while lock task is active is a no-op: a re-sent `config.apply`
 * (e.g. after an agent update) must not relaunch the kiosk launcher on a device already showing it.
 */
class KioskApplier(
    private val kiosk: KioskController,
    private val store: KioskStateStore,
    private val home: KioskHomeSwitch,
    private val homeComponent: ComponentName,
    /** Whether lock task is active now ([KioskController.isLocked]). */
    private val isLockTaskActive: () -> Boolean,
) {
    suspend fun enter(p: KioskApplyPayload): KioskResult {
        if (store.load() == p && isLockTaskActive()) return KioskResult.Ok
        val features = lockTaskFeatures(
            KioskToggles(
                home = p.features.home, recents = p.features.recents, notifications = p.features.notifications,
                systemInfo = p.features.systemInfo, keyguard = p.features.keyguard, lockButtons = p.features.lockButtons,
            ),
        )
        val allowed = (p.allowedPackages + listOfNotNull(p.pinPackage)).distinct()
        home.setClaimEnabled(true)
        return when (val r = kiosk.enter(homeComponent, allowed, features)) {
            KioskResult.Ok -> { store.save(p); home.showLauncher(); r }
            else -> { home.setClaimEnabled(false); r } // never leave a non-kiosk device claiming HOME
        }
    }

    suspend fun exit(): KioskResult = when (val r = kiosk.exit()) {
        KioskResult.Ok -> { store.save(null); home.setClaimEnabled(false); home.showOemHome(); r }
        else -> r
    }

    /** True when a kiosk payload is persisted (device believes it is / should be in kiosk). */
    suspend fun isPersisted(): Boolean = store.load() != null
}
