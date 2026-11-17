package com.mdmesh.kiosk

import java.text.Normalizer

/**
 * The exit PIN's keyboard. The console and server take only 6 to 12 digits for a new PIN, but a configuration saved
 * before that rule may still carry any text, and its devices keep working with it. So the device never refuses a PIN
 * for its format: it only picks the keyboard, a number pad for a PIN of digits and the full keyboard otherwise.
 */
object ExitPinFormat {
    /**
     * Whether [pin] is digits only, so a number pad can type it. Full-width digits count: the PIN check normalizes
     * both sides (NFKC), so the pad's ASCII digits match them.
     */
    fun numeric(pin: String?): Boolean {
        if (pin.isNullOrEmpty()) return false
        val normalized = Normalizer.normalize(pin, Normalizer.Form.NFKC)
        return normalized.isNotEmpty() && normalized.all { it in '0'..'9' }
    }
}
