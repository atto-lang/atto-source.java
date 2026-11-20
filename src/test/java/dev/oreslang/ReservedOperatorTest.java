package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** % is reserved; single ^ is return-only and ^^ is checked i64 bitwise XOR. */
final class ReservedOperatorTest {
    private static void reserved(String source, String operator, String replacement) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(error.getMessage().contains("'" + operator + "' is reserved"), error.getMessage());
        assertTrue(error.getMessage().contains(replacement), error.getMessage());
    }

    @Test
    void percentAndSingleCaretAreRejected() {
        reserved("fnc f(int a, int b): int { return a % b; }", "%", "Integer.rem");
        reserved("fnc f(int a, int b): int { return a ^ b; }", "^", "^^");
        reserved("fnc f(int a): int { return 1 + a % 2; }", "%", "Integer.mod");
    }
}
