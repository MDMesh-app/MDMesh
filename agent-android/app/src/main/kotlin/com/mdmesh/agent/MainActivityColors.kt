package com.mdmesh.agent

/** The status screen's colours (ARGB), as plain ints so their contrast is unit-tested. */
internal object MainActivityColors {
    const val INK = 0xFF0E1117.toInt()
    const val TEXT = 0xFFE8EEF4.toInt()
    const val MUTED = 0xFF8693A4.toInt()
    const val FAINT = 0xFF5C6675.toInt()
    const val SIGNAL = 0xFFF4B942.toInt()
    const val OK = 0xFF3FD08A.toInt()

    /** Warnings; 5.6:1 on [INK]. */
    const val ALERT = 0xFFF2545B.toInt()
}
