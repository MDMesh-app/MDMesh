/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.util;

/** Makes text from an untrusted source (a device, say) safe to put into an email, a log line or similar. */
public final class UntrustedText {

    private static final String ELLIPSIS = "…";

    private UntrustedText() {
    }

    /**
     * The text as one line, safe to show: every control character (CR and LF included), format character (bidi
     * overrides, zero-width), line or paragraph separator, and unpaired surrogate becomes a space, so nothing can start a
     * new line or reorder what a reader sees. Runs of spaces collapse to one, and the result is cut to {@code max}
     * characters, ending in "…" and never splitting a surrogate pair.
     *
     * @return the cleaned text, or null when nothing printable is left
     */
    public static String oneLine(String text, int max) {
        if (text == null) {
            return null;
        }
        String s = collapse(text, max).trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() <= max ? s : cut(s, max);
    }

    /** Unsafe characters and whitespace become single spaces. Stops once the text is known to be too long. */
    private static String collapse(String text, int max) {
        StringBuilder sb = new StringBuilder(Math.min(text.length(), max + 1));
        boolean lastWasSpace = true; // drops leading blanks
        for (int i = 0; i < text.length() && sb.length() <= max; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (unsafe(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (!lastWasSpace) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
            } else {
                sb.appendCodePoint(cp);
                lastWasSpace = false;
            }
        }
        return sb.toString();
    }

    /** {@code s} cut to {@code max} characters including the ellipsis. */
    private static String cut(String s, int max) {
        int end = max - ELLIPSIS.length();
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end).trim() + ELLIPSIS;
    }

    private static boolean unsafe(int cp) {
        switch (Character.getType(cp)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
            case Character.SURROGATE:
            case Character.PRIVATE_USE:
            case Character.UNASSIGNED:
                return true;
            default:
                return false;
        }
    }
}
