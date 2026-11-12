package com.mdmesh.agent.ui

import java.util.Locale

/**
 * The placeholder letter of `docs/kiosk-background-scrim.md` (Label lines, 7): the capped label's first grapheme with a
 * visible code point, upper-cased; null when there is none. The agent itself never draws one (PackageManager always
 * gives an app an icon); the console's preview does. It lives here, test-only, so the shared vectors check the agent's
 * graphemes and case mapping against the console's rule.
 */
fun iconLetter(label: String, graphemes: Graphemes): String? {
    val text = LabelLines.cap(label, graphemes)
    var start = 0
    for (end in graphemes.ends(text)) {
        val cluster = text.substring(start, end)
        start = end
        if (cluster.codePoints().anyMatch { !invisible(it) }) return cluster.uppercase(Locale.ROOT)
    }
    return null
}

private fun invisible(cp: Int): Boolean = when (android.icu.lang.UCharacter.getType(cp).toByte()) {
    Character.FORMAT, Character.CONTROL, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR,
    -> true
    else -> false
}
