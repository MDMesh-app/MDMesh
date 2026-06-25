package com.mdmesh.kiosk

/**
 * In-memory [FaultStore] with no Android dependency. Used by unit tests for
 * [CrashLoopGuard]; also handy for previews. Not durable across process death — do
 * not use in production.
 */
class InMemoryFaultStore : FaultStore {

    override var counter: Int = 0
        private set

    override var lastFaultTime: Long = -1L // -1 == never faulted (0 is a valid clock value)
        private set

    override fun write(counter: Int, lastFaultTime: Long) {
        this.counter = counter
        this.lastFaultTime = lastFaultTime
    }
}
