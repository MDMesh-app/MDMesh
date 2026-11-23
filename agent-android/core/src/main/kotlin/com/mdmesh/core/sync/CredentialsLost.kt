package com.mdmesh.core.sync

/**
 * Whether this device's credentials still count as lost, decided without comparing wall-clock times (a kiosk's clock
 * jumps: no RTC after a reboot, NTP corrections, a wrong year).
 *
 * When the credentials are marked lost, the mark stores the check-in success marker of that moment ([markerOf]: the
 * last success's [SyncStatus.lastSuccessAtMs], or [NO_SUCCESS]). They stay lost while that is still the last success;
 * any later successful check-in records a new one, which clears it, whatever the clock says.
 *
 * [SyncStatus.recordSuccess] is the only writer of the last success, once per successful check-in. A new success
 * stamped with exactly the stored value (the clock set back to that very millisecond) would leave the warning up
 * until the next check-in; it never hides a loss.
 */
object CredentialsLost {

    /** The marker of "no successful check-in yet". */
    const val NO_SUCCESS: Long = Long.MIN_VALUE

    /** What a mark made now stores. */
    fun markerOf(lastSuccessAtMs: Long?): Long = lastSuccessAtMs ?: NO_SUCCESS

    /** [mark]: the stored marker, or null when the credentials were never marked lost (or the mark was cleared). */
    fun stillLost(mark: Long?, lastSuccessAtMs: Long?): Boolean = mark != null && mark == markerOf(lastSuccessAtMs)
}
