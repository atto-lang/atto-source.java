package dev.oreslang.parser;

/** Shared checked-i64 source negotiation before any lexer/token or compiler work. */
public final class CheckedI64SourceHeader {
    private CheckedI64SourceHeader() { }

    /**
     * A misspelled, displaced or CRLF checked directive is an error, not a
     * request to silently execute wrapping arithmetic. Normal unmarked
     * sources remain unchanged. Only full comment lines are directives.
     */
    public static boolean isEnabled(String text) {
        final String prefix = "// oreslang:checked-i64";
        final String directive = "// oreslang:checked-i64-v1";
        final boolean enabled = text.startsWith(directive + "\n");
        for (int pos = 0; pos < text.length();) {
            int end = text.indexOf('\n', pos);
            int stop = end < 0 ? text.length() : end;
            int offset = pos;
            // A UTF-8 BOM is not part of the exact source opt-in.
            if (pos == 0 && text.charAt(0) == '\uFEFF') ++offset;
            // The lexer also ignores CR before comments. Do not allow a
            // CR-prefixed checked directive to select legacy wrapping i64.
            while (offset < stop && (text.charAt(offset) == ' '
                    || text.charAt(offset) == '\t'
                    || text.charAt(offset) == '\r')) ++offset;
            if (text.startsWith(prefix, offset)
                    && (!enabled || pos != 0 || offset != 0
                        || !text.substring(pos, stop).equals(directive))) {
                throw new IllegalArgumentException(
                    "checked-i64-v1: malformed, duplicated, displaced or unsupported directive");
            }
            if (end < 0) break;
            pos = end + 1;
        }
        // A checked marker following source tokens or a closed block comment
        // must not silently select wrapping arithmetic. Inspect only real
        // line comments, not marker-like text in strings or nested comments.
        int blockDepth = 0;
        char quote = 0;
        for (int i = 0; i < text.length();) {
            char ch = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (quote != 0) {
                if (ch == '\\' && i + 1 < text.length()) { i += 2; continue; }
                if (ch == quote) quote = 0;
                ++i;
                continue;
            }
            if (blockDepth != 0) {
                if (ch == '/' && next == '*') { ++blockDepth; i += 2; continue; }
                if (ch == '*' && next == '/') { --blockDepth; i += 2; continue; }
                ++i;
                continue;
            }
            if (ch == '"' || ch == '\'') { quote = ch; ++i; continue; }
            if (ch == '/' && next == '*') { blockDepth = 1; i += 2; continue; }
            if (ch == '/' && next == '/') {
                if (i != 0 && text.startsWith(prefix, i)) {
                    throw new IllegalArgumentException(
                        "checked-i64-v1: displaced directive after source tokens");
                }
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end + 1;
                continue;
            }
            ++i;
        }

        return enabled;
    }

}
