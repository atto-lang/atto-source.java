package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java lexer regressions integrating drafts #3114, #3116, #3123, #3125.
 * Host compiler/source admission only, not AttoLang guest or native AOT proof.
 */
final class LexerIntegratedSourceAdmissionTest {
    private static final String SAFE = "pub fnc main(): int { return 42; }";

    private static void rejects(String source, int line, int column, String reason) {
        String expected = "Oreslang lexer error at " + line + ":" + column + ": " + reason;
        Lexer lexer = new Lexer(source);
        for (int repeat = 0; repeat < 2; repeat++) {
            assertEquals(expected,
                    assertThrows(IllegalArgumentException.class, lexer::scan).getMessage());
        }
    }

    @Test void rejectsBidiControlsEvenWhenHiddenInCommentsStringsOrTemplates() {
        for (int value : new int[] {
                0x061c, 0x200e, 0x200f, 0x202a, 0x202b, 0x202c,
                0x202d, 0x202e, 0x2066, 0x2067, 0x2068, 0x2069 }) {
            String control = Character.toString(value);
            String reason = "source contains bidirectional Unicode control";
            rejects("/*" + control + "*/ " + SAFE, 1, 3, reason);
            rejects("\"" + control + "\"", 1, 2, reason);
            rejects("`" + control + "`", 1, 2, reason);
        }
    }

    @Test void rejectsUnicodeLineSeparatorsAcrossCrLfAndComments() {
        for (int value : new int[] {0x0085, 0x2028, 0x2029}) {
            rejects("x\r\n/*" + Character.toString(value) + "*/", 2, 3,
                    "source contains Unicode line separator");
        }
    }

    @Test void rejectsRawTerminalControlsInTriviaAndLiterals() {
        for (int value : new int[] {0x001b, 0x007f}) {
            String control = Character.toString(value);
            String reason = "source contains terminal control";
            rejects("/*" + control + "*/", 1, 3, reason);
            rejects("x\r\n\"" + control + "\"", 2, 2, reason);
            rejects("`" + control + "`", 1, 2, reason);
        }
    }

    @Test void reportsFirstInvalidSourceUnitOnly() {
        rejects("/*" + (char) 0x202e + "\0*/", 1, 3,
                "source contains bidirectional Unicode control");
        rejects("/*\0" + (char) 0x001b + "*/", 1, 3,
                "source contains NUL byte");
        rejects("x" + (char) 0xd800 + (char) 0x202e, 1, 2,
                "source contains invalid UTF-16");
        rejects("x\r\n" + (char) 0x007f + "\0", 2, 1,
                "source contains terminal control");
    }

    @Test void countsUtf16ColumnsNotCodePointsOrUtf8Bytes() {
        String emoji = Character.toString(0x1f600);
        rejects("/*" + emoji + (char) 0x202e + "*/", 1, 5,
                "source contains bidirectional Unicode control");
        rejects("x" + emoji + "\0", 1, 4, "source contains NUL byte");
        rejects("x" + emoji + (char) 0xdc00, 1, 4,
                "source contains invalid UTF-16");
        rejects("x" + "e" + (char) 0x0301 + "\0", 1, 4,
                "source contains NUL byte");
    }

    @Test void rescansSuccessAndPartialFailureDeterministically() {
        Lexer lexer = new Lexer("/* café 😀 */\r\n" + SAFE);
        List<Token> first = lexer.scan();
        List<Token> second = lexer.scan();
        assertEquals(first, second);
        assertNotSame(first, second);
        assertEquals(1L, first.stream().filter(t -> t.type() == Token.Type.EOF).count());
        assertEquals(Token.Type.PUB, second.getFirst().type());
        assertEquals(2, second.getFirst().line());

        Lexer broken = new Lexer("let value = 12;\r\n/* unclosed");
        String error = assertThrows(IllegalArgumentException.class, broken::scan).getMessage();
        assertEquals(error, assertThrows(IllegalArgumentException.class, broken::scan).getMessage());
    }

    @Test void permitsOrdinaryUnicodeCommentsWhitespaceAndStrings() {
        assertDoesNotThrow(() -> new Lexer("/* café 😀 */\r\n\t" + SAFE).scan());
        assertDoesNotThrow(() -> new Lexer("\"ordinary string\"").scan());
    }
}
