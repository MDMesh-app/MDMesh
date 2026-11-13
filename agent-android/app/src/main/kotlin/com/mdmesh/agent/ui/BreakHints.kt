package com.mdmesh.agent.ui

/**
 * [s] with a zero-width space after each '/', '.', '_', '-' and ':', so a long ID or URL that has to wrap (a large
 * font) breaks between its parts ("https://mdm." / "example.com") rather than mid-word. Display only: copying and
 * TalkBack use the value itself.
 */
fun breakHints(s: String): String = buildString(s.length + s.count { it in BREAK_AFTER }) {
    for (c in s) {
        append(c)
        if (c in BREAK_AFTER) append(ZERO_WIDTH_SPACE)
    }
}

private const val ZERO_WIDTH_SPACE = '\u200B'
private const val BREAK_AFTER = "/._-:"
