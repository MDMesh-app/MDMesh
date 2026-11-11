package com.mdmesh.kiosk

/**
 * Where [KioskReturnPolicy] keeps a crash-loop hold, so it outlives the launcher activity (a recreation, process
 * death). [heldBoot] is the boot the hold started in (null: not holding). A later boot doesn't inherit it: after a
 * reboot [CrashLoopGuard]'s own window decides again, as at any start.
 */
interface HoldStore {
    val heldBoot: Long?

    /** Persist [boot] as the boot a hold started in, or null to end the hold. Written at once. */
    fun writeHeld(boot: Long?)
}

/** In-memory [HoldStore] for tests: not durable. */
class InMemoryHoldStore : HoldStore {
    override var heldBoot: Long? = null
        private set

    override fun writeHeld(boot: Long?) {
        heldBoot = boot
    }
}
