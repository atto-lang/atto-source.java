package dev.oreslang.ast;

import java.nio.charset.StandardCharsets;

/**
 * Lexical boundary for compiler-created enum/pattern declaration ASTs.
 *
 * Mirrors the current Lexer IDENT character grammar, including its BMP-only
 * char scanner and ASCII numeric continuation. This is NOT nominal-resolution
 * authority: a successful check does not prove imports, enum ownership,
 * visibility, exhaustiveness or runtime admission.
 */
public final class EnumAstIdentity {
    /** Keep within the shared eos2 canonical-symbol byte budget. */
    public static final int MAX_ID_BYTES = 512;

    private EnumAstIdentity() { }

    public static void requireSimple(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_ID_BYTES) {
            throw new IllegalArgumentException("invalid or oversized enum identifier");
        }
        if (!start(text.charAt(0))) {
            throw new IllegalArgumentException("enum identifier must start with a letter or underscore");
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!part(ch) || Character.isISOControl(ch)
                    || Character.isWhitespace(ch) || Character.isSpaceChar(ch)
                    || forbiddenInvisible(ch)) {
                throw new IllegalArgumentException("invalid enum identifier character");
            }
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_ID_BYTES) {
            throw new IllegalArgumentException("enum identifier exceeds UTF-8 byte limit");
        }
    }

    /**
     * Current parser grammar: Owner.Case or ...Owner.
     * Nested dots and unqualified case spellings are NOT valid ASTs.
     */
    public static void requirePatternAlternative(String alternative) {
        if (alternative == null || alternative.isEmpty()) {
            throw new IllegalArgumentException("null or empty enum pattern alternative");
        }
        if (alternative.startsWith("...")) {
            requireSimple(alternative.substring(3));
            return;
        }
        int dot = alternative.indexOf('.');
        if (dot < 1 || dot == alternative.length() - 1
                || alternative.indexOf('.', dot + 1) >= 0) {
            throw new IllegalArgumentException("pattern must be Owner.Case or ...Owner");
        }
        requireSimple(alternative.substring(0, dot));
        requireSimple(alternative.substring(dot + 1));
    }

    private static boolean start(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean part(char c) {
        return start(c) || (c >= '0' && c <= '9');
    }

    private static boolean forbiddenInvisible(char c) {
        return c == '\u061c' || (c >= '\u200b' && c <= '\u200f')
                || (c >= '\u202a' && c <= '\u202e')
                || c == '\u2060' || (c >= '\u2066' && c <= '\u2069')
                || c == '\ufeff';
    }
}
