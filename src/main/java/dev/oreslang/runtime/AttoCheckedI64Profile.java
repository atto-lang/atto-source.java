package dev.oreslang.runtime;

/**
 * Opt-in checked-i64 source admission. The exact first-line directive is a
 * source profile, not a best-effort hint or an ambient host permission.
 *
 * <p>Like the JavaScript program-v2 scanner, only actual line comments can
 * select/reject a directive. Quoted text and nested block comments never do.
 * Unknown, displaced or duplicate directives fail closed before typechecking.
 */
public final class AttoCheckedI64Profile {
    private static final String DIRECTIVE = "// oreslang:checked-i64-v1\n";
    private static final String PREFIX = "oreslang:checked-i64";

    private AttoCheckedI64Profile() { }

    public static boolean select(String source) {
        if (source == null) throw new IllegalArgumentException("null Atto source");
        boolean enabled = source.startsWith(DIRECTIVE);
        int blockDepth = 0;
        char quote = 0;
        for (int i = 0; i < source.length();) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (quote != 0) {
                if (ch == '\\' && i + 1 < source.length()) { i += 2; continue; }
                if (ch == quote) quote = 0;
                i++;
                continue;
            }
            if (blockDepth != 0) {
                if (ch == '/' && next == '*') { blockDepth++; i += 2; continue; }
                if (ch == '*' && next == '/') { blockDepth--; i += 2; continue; }
                i++;
                continue;
            }
            if (ch == '"' || ch == '\'') { quote = ch; i++; continue; }
            if (ch == '/' && next == '*') { blockDepth = 1; i += 2; continue; }
            if (ch == '/' && next == '/') {
                int end = source.indexOf('\n', i);
                int stop = end < 0 ? source.length() : end;
                int marker = i + 2;
                while (marker < stop && " \t\r\u000b\f".indexOf(source.charAt(marker)) >= 0) marker++;
                if (source.startsWith(PREFIX, marker) && (!enabled || i != 0)) {
                    throw new IllegalArgumentException(
                            "checked-i64-v1: malformed, duplicated, displaced or unsupported directive");
                }
                i = end < 0 ? stop : end + 1;
                continue;
            }
            i++;
        }
        return enabled;
    }
}
