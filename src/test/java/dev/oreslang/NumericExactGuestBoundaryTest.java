package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guest-source boundaries for host exact-numeric carriers.
 *
 * A declared decimal/bigint local must not remain an untagged Java Long that
 * executes binary64 division, or a Double masquerading as a Decimal.
 */
final class NumericExactGuestBoundaryTest {
    @Test
    void compileTimeRejectsDecimalAndBinaryFloatArithmetic() {
        for (String operation : new String[]{"a + b", "a - b", "a * b", "a / b", "a % b"}) {
            String program = """
                    fnc invalid(decimal a, float b): decimal {
                      return %s;
                    }
                    """.formatted(operation);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(program)), operation);
            assertTrue(error.getMessage().contains("explicit conversion"),
                    () -> operation + ": " + error.getMessage());
        }
    }

    @Test
    void compileTimeAllowsExactDecimalAndIntegerArithmetic() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc add(decimal a, int b): decimal {
                  return a + b;
                }
                fnc multiply(decimal a, decimal b): decimal {
                  return a * b;
                }
                """)));
    }

    @Test
    void declaredExactLocalsKeepLargeIntegersAndDecimalFractions() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      val decimal exact = 9007199254740993;
                      val decimal one = 1;
                      val bigint big = 9007199254740993;
                      val decimal quarter = 4;
                      return (exact + one) eq 9007199254740994
                        && (big + 2) eq 9007199254740995
                        && (one / quarter) eq 0.25;
                    }
                    """).asBoolean());
        }
    }

    @Test
    void exactNumericsPropagateThroughDeclaredFunctionReturnTypes() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    fnc exactDecimal(int n): decimal {
                      return n;
                    }
                    fnc exactBigInt(int n): bigint {
                      return n;
                    }
                    pub routine main(): bool {
                      return (exactDecimal(9007199254740993) + 1) eq 9007199254740994
                        && (exactBigInt(9007199254740993) + 1) eq 9007199254740994;
                    }
                    """).asBoolean());
        }
    }
}
