package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.CompletableDeferred

// The KioskApplier tests' fakes: a controller that records every call, HOME, a journal and a store that can be held.

internal class FakeController(
    private var enterResult: KioskResult,
    private val exitResult: KioskResult = KioskResult.Ok,
    var volumeResult: KioskResult = KioskResult.Ok,
) : KioskController {
    var entered: List<String>? = null; var exited = false
    /** Every enter call's allowlist, in order; [failFor] makes an enter with that allowlist fail. */
    val enters = mutableListOf<List<String>>()
    var failFor: List<String>? = null
    /** Every setVolumeLocked call, in order. */
    val volume = mutableListOf<Boolean>()
    /** Every setSafeModeBlocked call, in order. */
    val safeMode = mutableListOf<Boolean>()
    var safeModeResult: KioskResult = KioskResult.Ok
    override fun setSafeModeBlocked(blocked: Boolean): KioskResult { safeMode += blocked; return safeModeResult }
    /** Every enter call's lock-task feature flags, in order. */
    val featureFlags = mutableListOf<Int>()
    /** Runs before an enter takes effect: a test throws [ProcessDeath] from it to cut the process off there. */
    var beforeEnter: (List<String>) -> Unit = {}
    override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult {
        beforeEnter(allowedPackages)
        entered = allowedPackages; enters += allowedPackages; featureFlags += features
        return if (allowedPackages == failFor) KioskResult.Failed("dpm") else enterResult
    }
    override fun exit(): KioskResult { exited = true; return exitResult }
    override fun setVolumeLocked(locked: Boolean): KioskResult { volume += locked; return volumeResult }
    override fun isLocked(context: android.content.Context): Boolean = false
    override fun allowedPackages(): List<String> = entered ?: emptyList()
}

internal class FakeHome : KioskHomeSwitch {
    val log = mutableListOf<String>()
    override fun setClaimEnabled(enabled: Boolean) { log += "claim:$enabled" }
    override fun showLauncher() { log += "launcher" }
    override fun showOemHome() { log += "oem" }
}

internal class MemJournal : ExitJournal {
    var saved: ExitJournal.Pending? = null
    override fun pending() = saved
    override fun begin(p: ExitJournal.Pending): Boolean { saved = p; return true }
    override fun end(): Boolean { saved = null; return true }
}

/** Delegates to [InMemoryKioskStateStore]; the first load() reads, then holds that value until [gate] completes. */
internal class GatedFirstLoadStore(
    private val inner: KioskStateStore = InMemoryKioskStateStore(),
) : KioskStateStore by inner {
    val gate = CompletableDeferred<Unit>()
    private var loads = 0
    override suspend fun load(): KioskApplyPayload? {
        val v = inner.load()
        if (loads++ == 0) gate.await()
        return v
    }
}
