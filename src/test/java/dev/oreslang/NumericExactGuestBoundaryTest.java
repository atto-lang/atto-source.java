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
    void mutableExactNumericBindingsNeverRevertToBinary64OrLong() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      let decimal exact = 1;
                      exact = 9007199254740993;
                      let bigint big = 1;
                      big = 9223372036854775807;
                      return (exact / 1) eq 9007199254740993
                        && (big + 1) > 9223372036854775807;
                    }
                    """).asBoolean());
        }
    }

    @Test
    void exactFunctionArgumentsArePromotedBeforeNumericOperations() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    fnc identityThroughDecimalDivision(decimal n): decimal {
                      return n / 1;
                    }
                    fnc incrementBigInt(bigint n): bigint {
                      return n + 1;
                    }
                    pub routine main(): bool {
                      return identityThroughDecimalDivision(9007199254740993)
                           eq 9007199254740993
                        && incrementBigInt(9223372036854775807)
                           > 9223372036854775807;
                    }
                    """).asBoolean());
        }
    }

    @Test
    void mathAbsAndNegPreserveExactGuestDomains() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      val bigint negativeBig = -9007199254740993;
                      val decimal one = 1;
                      val decimal four = 4;
                      val decimal negativeQuarter = -(one / four);
                      return Math.abs(negativeBig) eq 9007199254740993
                        && Math.neg(negativeBig) eq 9007199254740993
                        && Math.abs(negativeQuarter) eq 0.25
                        && Math.neg(negativeQuarter) eq 0.25;
                    }
                    """).asBoolean());
        }
    }

    @Test
    void mathSigned64MinimumNeverWraps() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            RuntimeException abs = assertThrows(RuntimeException.class, () ->
                    context.eval(OresLanguage.ID, """
                            pub routine main(): int {
                              return Math.abs(-9223372036854775808);
                            }
                            """));
            assertTrue(abs.getMessage() == null || !abs.getMessage().isBlank());

            RuntimeException neg = assertThrows(RuntimeException.class, () ->
                    context.eval(OresLanguage.ID, """
                            pub routine main(): int {
                              return Math.neg(-9223372036854775808);
                            }
                            """));
            assertTrue(neg.getMessage() == null || !neg.getMessage().isBlank());
        }
    }

    @Test
    void userMathClassShadowsCompilerIntrinsic() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    define class Math as
                      static fnc abs(int value): int { return 7; }
                      static fnc neg(int value): int { return 8; }
                    end
                    pub routine main(): bool {
                      return Math.abs(-42) eq 7 && Math.neg(42) eq 8;
                    }
                    """).asBoolean());
        }
    }

    @Test
    void preciseCarrierAdmissionRejectsFloatAndExtremeScale() {
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.math.ExactScalarMath.admitDecimal(0.1d));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.math.ExactScalarMath.admitInteger(0.1d));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.math.ExactScalarMath.admitDecimal(
                        new java.math.BigDecimal("1e-20000")));
        assertEquals(new java.math.BigInteger("9223372036854775807"),
                dev.oreslang.math.ExactScalarMath.admitInteger(Long.MAX_VALUE));
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
