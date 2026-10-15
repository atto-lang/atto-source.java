package dev.oreslang;

import dev.oreslang.parser.CheckedI64SourceHeader;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source negotiation must be identical in execution and HIR export. */
final class CheckedI64SourceHeaderTest {
    private static final String DIRECTIVE = "// oreslang:checked-i64-v1\n";
    private static final String MAIN = "pub fnc main(): int { return 43; }\n";

    private static void rejected(String source) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> CheckedI64SourceHeader.isEnabled(source),
                "checked profile must not silently fall back to legacy wrapping");
        assertTrue(failure.getMessage().contains("checked-i64-v1"));
    }

    @Test
    void exactDirectiveAndUnmarkedCommentsRetainTheirMeaning() {
        assertTrue(CheckedI64SourceHeader.isEnabled(DIRECTIVE + MAIN));
        assertFalse(CheckedI64SourceHeader.isEnabled(MAIN));
        // A genuine inline line-comment directive after tokens is displaced.
        rejected("pub fnc main(): int { return 43; } // oreslang:checked-i64-v1\n");
        assertFalse(CheckedI64SourceHeader.isEnabled("// unrelated\n" + MAIN));
    }

    @Test
    void markerLookalikesInsideBlockCommentsMustNotOptInOrBeRejected() {
        assertFalse(CheckedI64SourceHeader.isEnabled(
                "/*\n// oreslang:checked-i64-v1\n*/\n" + MAIN));
        assertFalse(CheckedI64SourceHeader.isEnabled(
                "/*\n//  oreslang:checked-i64-v1\n*/\n" + MAIN));

        assertFalse(CheckedI64SourceHeader.isEnabled(
                "/* outer /* nested\n// oreslang:checked-i64-v2\n"
                + "*/ still outer */\n" + MAIN));
        assertFalse(CheckedI64SourceHeader.isEnabled(
                MAIN + "/*\n  // oreslang:checked-i64-v1\n*/\n"));
        assertTrue(CheckedI64SourceHeader.isEnabled(
                DIRECTIVE + "/*\n// oreslang:checked-i64-v1\n*/\n" + MAIN));
        assertFalse(CheckedI64SourceHeader.isEnabled(
                "\"// oreslang:checked-i64-v1\"\n" + MAIN));
        rejected("/* harmless */ // oreslang:checked-i64-v1\n");
        rejected("/* harmless */\n// oreslang:checked-i64-v1\n");
    }

    @Test
    void malformedOrDisplacedProfileCannotExecuteAsUnmarkedCode() {
        for (String source : new String[] {
                "\n" + DIRECTIVE + MAIN,
                " " + DIRECTIVE + MAIN,
                "\r" + DIRECTIVE + MAIN,
                "\r\t" + DIRECTIVE + MAIN,
                DIRECTIVE + "\r" + DIRECTIVE + MAIN,
                "\t" + DIRECTIVE + MAIN,
                "\uFEFF" + DIRECTIVE + MAIN,
                "// oreslang:checked-i64-v1\r\n" + MAIN,
                "//oreslang:checked-i64-v1\n" + MAIN,
                "//  oreslang:checked-i64-v1\n" + MAIN,
                "//\toreslang:checked-i64-v1\n" + MAIN,
                "// \toreslang:checked-i64-v1\n" + MAIN,
                "//\roreslang:checked-i64-v1\n" + MAIN,
                "//\u000Boreslang:checked-i64-v1\n" + MAIN,
                "//\foreslang:checked-i64-v1\n" + MAIN,
                "// oreslang:checked-i64-v2\n" + MAIN,
                "// oreslang:checked-i64-v1 trailing\n" + MAIN,
                DIRECTIVE + DIRECTIVE + MAIN,
                MAIN + DIRECTIVE,
                DIRECTIVE + "  // oreslang:checked-i64-v1\n" + MAIN,
                "// oreslang:checked-i64-v1"
        }) rejected(source);
    }

    @Test
    void deterministicMalformedSpacingInsideAndOutsideNestedComments() {
        for (int i = 0; i < 4096; i++) {
            String gap = i % 3 == 0
                    ? " ".repeat(i % 5 + 2)
                    : (i % 3 == 1 ? "\t" : " \t");
            String malformed = "//" + gap + "oreslang:checked-i64-v1\n";
            rejected(malformed + MAIN);
            rejected(DIRECTIVE + malformed + MAIN);
            String hidden = "/* outer /* nested\n" + malformed + "*/ outer */\n";
            assertFalse(CheckedI64SourceHeader.isEnabled(hidden + MAIN));
            assertTrue(CheckedI64SourceHeader.isEnabled(
                    DIRECTIVE + hidden + MAIN));
        }
    }

    @Test
    void deterministicManyLinePrefixesDoNotChangeExactOptIn() {
        Random random = new Random(20261015L);
        for (int i = 0; i < 4096; i++) {
            String innocent = "// other-" + Long.toUnsignedString(random.nextLong()) + "\n";
            assertTrue(CheckedI64SourceHeader.isEnabled(DIRECTIVE + innocent + MAIN));
            assertFalse(CheckedI64SourceHeader.isEnabled(innocent + MAIN));
            rejected(innocent + DIRECTIVE + MAIN);
            rejected(DIRECTIVE + innocent + DIRECTIVE + MAIN);
        }
    }
}
