package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AttoLang numerical semantics v1: strict integer division is checked and
 * truncated toward zero. These are real guest-language tests, not a host
 * arithmetic reference, and must execute on the exact PR head before merge.
 */
final class NumericIntegerDivisionV1Test {
    @Test
    void typeCheckerPreservesI64DivisionInsteadOfWideningToFloat() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc quotient(int a, int b): int { return a / b; }
                pub fnc main(): int { return quotient(-84, 2); }
                """)));
    }

    @Test
    void signedQuotientsTruncateTowardZeroAcrossFunctionBoundary() {
        String[][] cases = {
                {"-7", "3", "-2"}, {"7", "-3", "-2"}, {"-7", "-3", "2"},
                {"7", "3", "2"}, {"1", "2", "0"}, {"-1", "2", "0"},
                {"-9223372036854775808", "1", "-9223372036854775808"},
                {"9223372036854775807", "1", "9223372036854775807"}
        };
        for (String[] testCase : cases) {
            String guest = """
                    pub fnc quotient(int a, int b): int { return a / b; }
                    pub fnc main(): int { return quotient(%s, %s); }
                    """.formatted(testCase[0], testCase[1]);
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                assertEquals(Long.parseLong(testCase[2]),
                        context.eval(OresLanguage.ID, guest).asLong(),
                        testCase[0] + " / " + testCase[1]);
            }
        }
    }

    @Test
    void remainderKeepsDividendSignAndMinRemainderMinusOneIsZero() {
        String[][] cases = {
                {"-7", "3", "-1"}, {"7", "-3", "1"}, {"-7", "-3", "-1"},
                {"7", "3", "1"}, {"-9223372036854775808", "-1", "0"}
        };
        for (String[] testCase : cases) {
            String guest = """
                    pub fnc remainder(int a, int b): int { return a %% b; }
                    pub fnc main(): int { return remainder(%s, %s); }
                    """.formatted(testCase[0], testCase[1]);
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                assertEquals(Long.parseLong(testCase[2]),
                        context.eval(OresLanguage.ID, guest).asLong(),
                        testCase[0] + " % " + testCase[1]);
            }
        }
    }

    @Test
    void zeroDivisorAndZeroRemainderHaveGuestErrorIdentity() {
        for (String operation : new String[] { "/", "%" }) {
            String guest = """
                    pub fnc checked(int a, int b): int { return a %s b; }
                    pub fnc main(): int {
                      try {
                        return checked(7, 0);
                      } catch (err) {
                        if err is type DivisionByZeroError {
                          if err is type Error { return 42; }
                          return 2;
                        }
                        return 1;
                      } finally {
                      }
                    }
                    """.formatted(operation);
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                assertEquals(42L, context.eval(OresLanguage.ID, guest).asLong(),
                        "guest error identity lost for " + operation);
            }
        }
    }

    @Test
    void signedMinDividedByNegativeOneRaisesOverflowNotZeroDivisor() {
        String guest = """
                pub fnc checked(int a, int b): int { return a / b; }
                pub fnc main(): int {
                  try {
                    return checked(-9223372036854775808, -1);
                  } catch (err) {
                    if err is type ArithmeticOverflowError { return 42; }
                    return 1;
                  } finally {
                  }
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(OresLanguage.ID, guest).asLong());
        }
    }

    @Test
    void uncaughtZeroDivisorIsGuestErrorNotHostArithmeticException() {
        String guest = """
                pub fnc main(): int {
                  val int divisor = 0;
                  return 7 / divisor;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(PolyglotException.class,
                    () -> context.eval(OresLanguage.ID, guest));
            assertNotNull(failure.getMessage());
            assertTrue(failure.getMessage().contains("DivisionByZeroError"),
                    failure::getMessage);
            assertFalse(failure.getMessage().contains("java.lang.ArithmeticException"),
                    failure::getMessage);
        }
    }

    @Test
    void floatingDivisionRemainsBinaryFloatingDivision() {
        String guest = """
                pub fnc quotient(float a, float b): float { return a / b; }
                pub fnc main(): float { return quotient(7.0, 2.0); }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(3.5d, context.eval(OresLanguage.ID, guest).asDouble());
        }
    }
}
