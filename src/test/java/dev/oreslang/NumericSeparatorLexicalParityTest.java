package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NumericSeparatorLexicalParityTest {
    private static void parses(String expression) {
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc main(): int { return " + expression + "; }"), expression);
    }

    private static void rejectsAtLexer(String literal, String part) {
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> new Lexer(literal).scan(), literal);
        assertTrue(denied.getMessage().contains("malformed " + part + " separator"),
                denied::getMessage);
        assertTrue(denied.getMessage().startsWith("Oreslang lexer error at 1:"),
                denied::getMessage);
    }

    @Test
    void digitsBetweenSeparatorsAreAdmittedForAllNumericTokenKinds() {
        for (String integer : new String[] {
                "1_0", "9_223_372_036_854_775_807", "0_0_4_2"
        }) {
            assertEquals(Token.Type.INT, new Lexer(integer).scan().getFirst().type());
            parses(integer);
        }
        for (String floating : new String[] {
                "1_0.2_5", "1_2e3_0", "1.2_5e+1_0", "2_5E-1_0"
        }) {
            assertEquals(Token.Type.FLOAT, new Lexer(floating).scan().getFirst().type());
        }
        for (String imaginary : new String[] { "1_2i", "1.2_5i", "1e2_0i" }) {
            assertEquals(Token.Type.IMAG, new Lexer(imaginary).scan().getFirst().type());
        }
        parses("-9_223_372_036_854_775_808");
    }

    @Test
    void malformedIntegerSeparatorsAreRejectedBeforeAnyParserContext() {
        for (String invalid : new String[] {
                "1_", "1__2", "1___2", "1_+2", "1_-2", "1_ 2", "1_a", "12_i"
        }) {
            rejectsAtLexer(invalid, "integer");
        }
        for (String invalid : new String[] { "1.2_", "1.2__3", "1.2_i" }) {
            rejectsAtLexer(invalid, "fractional");
        }
        for (String invalid : new String[] { "1e2_", "1e2__3", "1.2e3_+5" }) {
            rejectsAtLexer(invalid, "exponent");
        }
    }

    @Test
    void signedI64BoundariesAreExactInIntegerExpressions() {
        parses("9_223_372_036_854_775_807");
        parses("-9_223_372_036_854_775_808");
        parses("-0_9223372036854775808");
        parses("0_9223372036854775807");
        parses("0_0_0");
        for (String invalid : new String[] {
                "9_223_372_036_854_775_808",
                "+9_223_372_036_854_775_808",
                "-9_223_372_036_854_775_809",
                "-0_9223372036854775809"
        }) {
            String source = "pub fnc main(): int { return " + invalid + "; }";
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(denied.getMessage().contains("integer literal overflows signed 64-bit"),
                    denied::getMessage);
        }
    }

    @Test
    void actorConfigurationCannotNormalizeMalformedIntegerSeparators() {
        for (String field : new String[] { "requestConcurrency", "messageConcurrency" }) {
            for (String invalid : new String[] { "1_", "1__0", "1___0", "1_-2", "1_ 0", "1_+2" }) {
                String source = "define http actor Service as "
                        + "int " + field + " = " + invalid + "; end";
                IllegalArgumentException denied = assertThrows(
                        IllegalArgumentException.class, () -> Parser.parse(source), source);
                assertTrue(denied.getMessage().contains("malformed integer separator"),
                        denied::getMessage);
            }
        }
        assertDoesNotThrow(() -> Parser.parse(
                "define http actor Service as "
                    + "int requestConcurrency = 1_0; "
                    + "int messageConcurrency = 2_0; end"));
    }
}
