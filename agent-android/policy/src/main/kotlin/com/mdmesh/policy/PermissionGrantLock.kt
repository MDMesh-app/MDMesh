package com.mdmesh.policy

/**
 * One lock for every writer of per-app permission grant states: the configuration's app permission rules
 * (`ConfigApplier`) and the kiosk guard (`KioskGuard`). Each takes it around a decision and its write, so neither
 * acts on what the other is changing (the guard reads the rules inside it, and leaves a pair a rule owns alone).
 * One instance per process (the agent's DI).
 */
class PermissionGrantLock {
    fun <T> withLock(block: () -> T): T = synchronized(this) { block() }

    /** Whether the calling thread holds it (tests). */
    fun isHeldByCurrentThread(): Boolean = Thread.holdsLock(this)
}
