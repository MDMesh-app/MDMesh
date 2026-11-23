package com.mdmesh.agent.ui

import kotlin.math.pow

/**
 * WCAG 2.x contrast between two opaque colours (`0xAARRGGBB`; alpha is ignored). Pure Kotlin, so it is unit-tested
 * on the JVM and can check colours that come from outside the theme, like an admin's kiosk colours.
 */
object Contrast {

    /** Body text needs this much against its background (WCAG AA). */
    const val TEXT_MIN = 4.5

    /** Field outlines, focus rings and icons need this much (WCAG 1.4.11). */
    const val UI_MIN = 3.0

    /** The contrast ratio, from 1.0 (same colour) to 21.0 (black on white). Order doesn't matter. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + OFFSET) / (minOf(la, lb) + OFFSET)
    }

    /** Relative luminance of an sRGB colour. */
    fun luminance(argb: Int): Double =
        R * channel(argb shr RED_SHIFT) + G * channel(argb shr GREEN_SHIFT) + B * channel(argb)

    /** A channel's linear value, looked up: the plates work out thousands of luminances a frame. */
    private fun channel(shifted: Int): Double = LINEAR[shifted and BYTE_MASK]

    /** The sRGB transfer of each 8-bit level, exactly as computed per call before (the same doubles). */
    private val LINEAR = DoubleArray(BYTE_MASK + 1) { level ->
        val c = level / BYTE_MAX
        if (c <= LINEAR_LIMIT) c / LINEAR_DIVISOR else ((c + GAMMA_OFFSET) / GAMMA_DIVISOR).pow(GAMMA)
    }

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE_MASK = 0xFF
    private const val BYTE_MAX = 255.0
    private const val OFFSET = 0.05
    private const val R = 0.2126
    private const val G = 0.7152
    private const val B = 0.0722
    private const val LINEAR_LIMIT = 0.04045
    private const val LINEAR_DIVISOR = 12.92
    private const val GAMMA_OFFSET = 0.055
    private const val GAMMA_DIVISOR = 1.055
    private const val GAMMA = 2.4
}
