package dev.oreslang.parser;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LexerSourceBidiAdmissionTest {
    private static final String SAFE = "pub fnc main(): int { return 42; }";
    private static final int[] BIDI_CONTROLS = {
        0x061c, 0x200e, 0x200f, 0x202a, 0x202b, 0x202c,
        0x202d, 0x202e, 0x2066, 0x2067, 0x2068, 0x2069
    };

    @Test void rejectsTrojanSourceControlsEvenInIgnoredComments() {
        for (int codepoint : BIDI_CONTROLS) {
            String control = Character.toString(codepoint);
            for (String source : new String[] {
                    SAFE + " // " + control,
                    "/* " + control + " */ " + SAFE }) {
                IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new Lexer(source).scan(),
                    "U+" + Integer.toHexString(codepoint));
                assertTrue(error.getMessage().contains("bidirectional Unicode control"));
            }
        }
    }

    @Test void permitsOrdinaryNonAsciiComments() {
        assertDoesNotThrow(() -> new Lexer("/* café */ " + SAFE).scan());
    }
}
