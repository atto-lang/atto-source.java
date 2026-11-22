package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-input diagnostics only; this is not guest or Native Image execution. */
final class LexerSourceAdmissionPositionTest {
    private static void rejectsAt(String source, String position, String detail) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> new Lexer(source).scan());
        assertEquals("Oreslang lexer error at " + position + ": " + detail,
                failure.getMessage());
    }

    @Test void reportsNulAfterCrLfEvenInsideIgnoredComment() {
        rejectsAt("x\r\n/* a\0 */", "2:5", "source contains NUL byte");
    }

    @Test void reportsUnpairedHighSurrogateAtEndOfSource() {
        rejectsAt("a\r\n" + (char) 0xd800, "2:1", "source contains invalid UTF-16");
    }

    @Test void reportsUnpairedLowSurrogateAfterValidSupplementaryCharacter() {
        rejectsAt("a" + Character.toString(0x1f600) + (char) 0xdc00, "1:4", "source contains invalid UTF-16");
    }

    @Test void reportsHighSurrogateBeforeNonLowSurrogateWithoutConsumingLocation() {
        rejectsAt("\r\na" + (char) 0xd800 + "b", "2:2", "source contains invalid UTF-16");
    }

    @Test void treatsLoneCrAndLfAsDistinctLineEndings() {
        rejectsAt("a\rb\n\0", "3:1", "source contains NUL byte");
    }

    @Test void preservesUtf16ColumnsAfterAstralAndCombiningText() {
        rejectsAt("x" + Character.toString(0x1f600) + "\0",
                "1:4", "source contains NUL byte");
        rejectsAt("x" + "e\u0301" + "\0",
                "1:4", "source contains NUL byte");
    }

    @Test void rejectsFirstInvalidSourceUnitWithoutSkippingLaterNul() {
        rejectsAt("x" + (char) 0xd800 + "\0",
                "1:2", "source contains invalid UTF-16");
        rejectsAt("x\0" + (char) 0xd800,
                "1:2", "source contains NUL byte");
    }

    @Test void rejectedSourceIsStableAcrossMultipleScans() {
        Lexer lexer = new Lexer("\r\n" + (char) 0xdc00);
        String expected = "Oreslang lexer error at 2:1: source contains invalid UTF-16";
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(expected, assertThrows(IllegalArgumentException.class,
                    lexer::scan).getMessage());
        }
    }

    @Test void acceptsValidSupplementaryUnicodeAndPreservesLexerLocations() {
        var result = new Lexer("/* " + Character.toString(0x1f600)
                + " */\r\npub fnc main(): int { return 42; }").scan();
        assertEquals(Token.Type.PUB, result.getFirst().type());
        assertEquals(2, result.getFirst().line());
        assertEquals(1, result.getFirst().column());
    }
}
