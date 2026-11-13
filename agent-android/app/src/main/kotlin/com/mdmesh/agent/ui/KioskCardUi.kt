package com.mdmesh.agent.ui

import androidx.annotation.StringRes
import com.mdmesh.agent.R

/**
 * What the status screen's Kiosk card shows. Return to kiosk is offered exactly as before the redesign: only to a
 * Device Owner that isn't locked and has a configured kiosk, and it is disabled with "Returning…" while a return
 * runs.
 */
data class KioskCardUi(
    @StringRes val pill: Int,
    val active: Boolean,
    @StringRes val body: Int,
    val returnVisible: Boolean,
    val returnEnabled: Boolean,
    @StringRes val returnLabel: Int,
    /** A return is running. */
    val returning: Boolean,
) {
    companion object {
        fun of(deviceOwner: Boolean, locked: Boolean, configured: Boolean, returning: Boolean): KioskCardUi {
            val canReturn = deviceOwner && !locked && configured
            return KioskCardUi(
                pill = if (locked) R.string.status_kiosk_on else R.string.status_kiosk_off,
                active = locked,
                body = when {
                    locked -> R.string.status_kiosk_body_locked
                    configured -> R.string.status_kiosk_body_ready
                    else -> R.string.status_kiosk_body_off
                },
                returnVisible = canReturn,
                returnEnabled = canReturn && !returning,
                returnLabel = if (returning) R.string.kiosk_return_busy else R.string.kiosk_return_action,
                returning = returning,
            )
        }
    }
}
