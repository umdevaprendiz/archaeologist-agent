package com.archaeologist.audit;

import tools.jackson.core.SerializableString;
import tools.jackson.core.io.CharacterEscapes;
import tools.jackson.core.io.SerializedString;

/**
 * JSON escaping for the audit log. On top of what JSON requires (quotes, backslashes and control codes
 * below U+0020), it escapes the characters that make text show differently from what it is, since the
 * audit log quotes the repository under analysis and people read it in terminals and editors:
 *
 * <ul>
 *   <li>DEL and the C1 control codes (U+0080 to U+009F): some terminals run them as commands;</li>
 *   <li>U+2028 and U+2029: some editors and tools take them as line breaks, splitting an event;</li>
 *   <li>the bidirectional controls: they reorder text on screen ("Trojan Source").</li>
 * </ul>
 *
 * <p>Everything else is written as is, so accented text, common in commit messages, stays readable.
 */
final class AuditEscapes extends CharacterEscapes {

    private static final long serialVersionUID = 1L;

    private final int[] ascii;

    AuditEscapes() {
        ascii = standardAsciiEscapesForJSON();
        ascii[0x7F] = ESCAPE_STANDARD;
    }

    @Override
    public int[] getEscapeCodesForAscii() {
        return ascii;
    }

    @Override
    public SerializableString getEscapeSequence(int ch) {
        return needsEscape(ch) ? new SerializedString(String.format("\\u%04X", ch)) : null;
    }

    static boolean needsEscape(int ch) {
        return (ch >= 0x80 && ch <= 0x9F)
                || ch == 0x2028 || ch == 0x2029
                || ch == 0x061C
                || ch == 0x200E || ch == 0x200F
                || (ch >= 0x202A && ch <= 0x202E)
                || (ch >= 0x2066 && ch <= 0x2069);
    }
}
