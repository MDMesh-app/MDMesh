package com.mdmesh.kiosk

/**
 * Safety valve for kiosk deployments: a misconfigured allowlist or a crashing kiosk
 * app can pin a Device-Owner device in an unrecoverable boot/crash loop. This guard
 * counts crashes inside a rolling time window; once the threshold is exceeded the
 * caller stops self-restarting and surfaces a recovery path (e.g. exit lock-task /
 * offer a launcher chooser) so a broken DPC cannot brick the device.
 *
 * Ported from Headwind MDM's `CrashLoopProtection` (Apache-2.0). The counting logic
 * is deliberately Android-free so it is unit-testable on the JVM:
 * - the clock is injected via [now];
 * - the persisted counter lives behind [FaultStore], with a SharedPreferences-backed
 *   impl ([SharedPrefsFaultStore]) for production and [InMemoryFaultStore] for tests.
 *
 * @property store persisted fault counter + last-fault timestamp.
 * @property now wall-clock time source in millis; injectable for tests.
 */
class CrashLoopGuard(
    private val store: FaultStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Register a crash/fault now.
     *
     * If the previous fault was longer than [LOOP_TIME_SPAN] ago (or none recorded),
     * the counter restarts at 1; otherwise it is incremented. The store is committed
     * synchronously because the process may be dying when this is called.
     */
    fun registerFault() {
        val faultTime = now()
        val lastFaultTime = store.lastFaultTime
        // lastFaultTime < 0 == "never faulted" (NOT 0, which is a valid clock value);
        // a fresh window also starts when the previous fault aged out.
        if (lastFaultTime < 0L || faultTime - lastFaultTime > LOOP_TIME_SPAN) {
            store.write(counter = 1, lastFaultTime = faultTime)
            return
        }
        store.write(counter = store.counter + 1, lastFaultTime = lastFaultTime)
    }

    /**
     * @return true if more than [LOOP_CRASHES] faults occurred within [LOOP_TIME_SPAN].
     *
     * If the last fault has aged out of the window the counter is reset and this
     * returns false, so a device that recovers is not stuck in the bail-out state.
     */
    fun isCrashLoopDetected(): Boolean {
        val faultTime = now()
        val lastFaultTime = store.lastFaultTime
        if (lastFaultTime < 0L) return false
        if (faultTime - lastFaultTime > LOOP_TIME_SPAN) {
            store.write(counter = 0, lastFaultTime = 0L)
            return false
        }
        return store.counter > LOOP_CRASHES
    }

    companion object {
        /** Rolling window for counting crashes, in millis. */
        const val LOOP_TIME_SPAN = 60_000L

        /** Crash count that must be exceeded within [LOOP_TIME_SPAN] to trip the guard. */
        const val LOOP_CRASHES = 3

        /** SharedPreferences file name for the persisted fault counter. */
        const val FAULT_PREFERENCE_NAME = "com.mdmesh.fault"
    }
}

/**
 * Persistence for [CrashLoopGuard]'s fault counter and last-fault timestamp.
 *
 * Writes must be durable enough to survive process death; the production impl
 * commits synchronously.
 */
interface FaultStore {
    val counter: Int
    val lastFaultTime: Long

    /** Atomically persist both fields. */
    fun write(counter: Int, lastFaultTime: Long)
}
