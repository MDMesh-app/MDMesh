package com.mdmesh.kiosk

/**
 * Where [KioskReturnPolicy] keeps a crash-loop hold, so it outlives the launcher activity (a recreation, process
 * death). [heldBoot] is the boot the hold started in (null: not holding). A later boot doesn't inherit it: after a
 * reboot [CrashLoopGuard]'s own window decides again, as at any start.
 */
interface HoldStore {
    val heldBoot: Long?

    /** Uptime (elapsedRealtime) when the hold started: tells boots apart where the ROM keeps no boot count. */
    val heldElapsed: Long

    /** Persist [boot] (and the uptime [elapsed]) as the hold's start, or null to end the hold. Written at once. */
    fun writeHeld(boot: Long?, elapsed: Long = 0L)
}

/** In-memory [HoldStore] for tests: not durable. */
class InMemoryHoldStore : HoldStore {
    override var heldBoot: Long? = null
        private set

    override var heldElapsed: Long = 0L
        private set

    override fun writeHeld(boot: Long?, elapsed: Long) {
        heldBoot = boot
        heldElapsed = elapsed
    }
}
