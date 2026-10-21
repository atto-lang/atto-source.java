package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SignedI64MinLiteralAdmissionTest {
    private static String program(String literal) {
        return "pub fnc boundary(): int { return " + literal + "; }";
    }

    @Test
    void acceptsBothInclusiveSignedI64Endpoints() {
        assertDoesNotThrow(() -> Parser.parse(program("-9223372036854775808")));
        assertDoesNotThrow(() -> Parser.parse(program("9223372036854775807")));
        assertDoesNotThrow(() -> Parser.parse(program("-9_223_372_036_854_775_808")));
    }

    @Test
    void rejectsMagnitudeOverflowAndMalformedSeparators() {
        for (String literal : new String[]{
                "9223372036854775808",
                "-9223372036854775809",
                "-922__3372036854775808",
                "-9223372036854775808_",
                "-(9223372036854775808)"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(program(literal)), literal);
        }
    }
}
