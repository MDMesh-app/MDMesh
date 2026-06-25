package com.mdmesh.kiosk

import android.content.ComponentName
import android.content.Context

/**
 * Result of a kiosk (lock-task) operation.
 *
 * Kiosk operations never throw: every failure mode (missing Device Owner, an
 * unsupported SDK level, or a thrown framework exception) is mapped to one of
 * these cases so the caller — typically a `:core` command handler — can turn it
 * into a command outcome without a try/catch.
 *
 * This mirrors the spirit of `:policy`'s `PolicyOutcome` but is defined locally so
 * `:kiosk` does not depend on `:policy`.
 */
sealed interface KioskResult {
    /** The operation completed successfully. */
    data object Ok : KioskResult

    /** The operation could not be performed and the reason is described in [reason]. */
    data class Failed(val reason: String) : KioskResult

    /**
     * The operation is not supported in this environment — e.g. the app is not the
     * Device Owner, or the running SDK is below the required level.
     */
    data object Unsupported : KioskResult
}

/**
 * Controls COSU (Corporate-Owned Single-Use) lock-task ("kiosk") mode via the
 * native [android.app.admin.DevicePolicyManager] lock-task APIs.
 *
 * Headwind's real COSU engine is closed-source ("Pro"); this is a clean-room
 * implementation built on the public framework APIs (`setLockTaskPackages`,
 * `setLockTaskFeatures`, `addPersistentPreferredActivity`). See the module README
 * for how `:app` wires the kiosk activity manifest hooks.
 *
 * Implementations must never throw — see [KioskResult].
 */
interface KioskController {

    /**
     * Enter lock-task mode.
     *
     * Allowlists [allowedPackages] (plus the agent's own package, so the kiosk
     * launcher itself can run), enables the standard lock-task UI features on
     * API 28+, and registers [homeComponent] as the persistent preferred HOME
     * activity so the agent owns the home button.
     *
     * This configures the device-level policy. The dedicated kiosk activity still
     * calls `Activity.startLockTask()` itself once it is in the foreground (it is
     * declared `android:lockTaskMode="if_whitelisted"` — see the README).
     *
     * [features] is a `LOCK_TASK_FEATURE_*` bitmask (see [lockTaskFeatures]) applied on API 28+.
     */
    fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult

    /**
     * Exit lock-task mode: clears the allowlist, releases the persistent HOME claim,
     * and resets lock-task features to the framework default.
     */
    fun exit(): KioskResult

    /** True if the device is currently pinned in lock-task mode. */
    fun isLocked(context: Context): Boolean

    /** The packages currently allowlisted for lock-task mode, or empty if unavailable. */
    fun allowedPackages(): List<String>
}
