package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.StrictIntegerArithmetic;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.Map;
import java.math.BigInteger;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
final class CheckedQuotientProfileForwardPortTest {
    private static final String CHECKED = """
            fnc divide(int a, int b): int { return a / b; }
            pub fnc main(): int { return 44 + divide(-7, 3); }
            """;
    private static final String LEGACY = """
            pub fnc main(): float { return 7 / 2; }
            """;

    private static double run(String source, String name) throws Exception {
        try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            return context.eval(Source.newBuilder(OresLanguage.ID, source, name)
                    .mimeType(OresLanguage.MIME_TYPE).build()).asDouble();
        }
    }

    @Test void checkedAttoAndLegacyOresKeepSeparateQuotientTypes() throws Exception {
        assertEquals(42.0, run(CHECKED, "quotient.atto"));
        assertEquals(3.5, run(LEGACY, "quotient.ores"));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(CHECKED));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(CHECKED));
    }

    @Test void checkedDirectiveSelectsSameSemanticsWithoutAttoExtension() throws Exception {
        String source = "// oreslang:checked-i64-v1\n" + CHECKED;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(42.0, run(source, "quotient.ores"));
        assertEquals(3.5, run("pub fnc main(): float { return 7.0 / 2.0; }", "floating.atto"));
    }

    @Test void linkedAdmissionSelectsProfileByUnitWithoutCacheCrossContamination() {
        var compiler = new IncrementalCompiler();
        assertDoesNotThrow(() -> compiler.compile(Map.of("quotient.atto", CHECKED)));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(Map.of("quotient.ores", CHECKED)));
        assertDoesNotThrow(() -> compiler.compile(Map.of("quotient.ores", LEGACY)));
        assertDoesNotThrow(() -> compiler.compile(Map.of("quotient.atto", CHECKED)));
    }

    @Test void signedBoundaryAndZeroAreDistinctFailures() throws Exception {
        assertEquals(-2L, StrictIntegerArithmetic.div(-7, 3));
        assertEquals(-2L, StrictIntegerArithmetic.div(7, -3));
        assertEquals(2L, StrictIntegerArithmetic.div(-7, -3));
        assertEquals(Long.MIN_VALUE, StrictIntegerArithmetic.div(Long.MIN_VALUE, 1));
        assertEquals(0L, StrictIntegerArithmetic.div(1, Long.MIN_VALUE));
        assertTrue(assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.div(7, 0))
                .getMessage().contains("ORES_I64_DIVISION_BY_ZERO"));
        assertTrue(assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.div(Long.MIN_VALUE, -1))
                .getMessage().contains("ORES_I64_OVERFLOW"));
        // Deterministic independent mathematical oracle, not a second
        // implementation of Java long division. Include nonrepresentable
        // quotients and values beyond the binary64 exact integer range.
        long[] edges = { Long.MIN_VALUE, Long.MIN_VALUE + 1, -9007199254740993L,
                -7, -3, -2, -1, 0, 1, 2, 3, 7, 9007199254740993L,
                Long.MAX_VALUE - 1, Long.MAX_VALUE };
        for (long a : edges) for (long b : edges) checkAgainstOracle(a, b);
        Random random = new Random(518231);
        for (int i = 0; i < 100000; i++) checkAgainstOracle(random.nextLong(), random.nextLong());
        for (String[] failure : new String[][] {
                {"7", "0", "ORES_I64_DIVISION_BY_ZERO"},
                {"-9223372036854775808", "-1", "ORES_I64_OVERFLOW"}
        }) {
            String guest = "fnc divide(int a, int b): int { return a / b; } "
                    + "pub fnc main(): int { return divide(" + failure[0] + ", " + failure[1] + "); }";
            var error = assertThrows(PolyglotException.class, () -> run(guest, "fault.atto"));
            assertTrue(error.getMessage().contains(failure[2]), error::getMessage);
        }
    }

    private static void checkAgainstOracle(long a, long b) {
        if (b == 0) {
            assertTrue(assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.div(a, b))
                    .getMessage().contains("ORES_I64_DIVISION_BY_ZERO"));
            assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.rem(a, b));
            assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.mod(a, b));
            return;
        }
        BigInteger dividend = BigInteger.valueOf(a);
        BigInteger divisor = BigInteger.valueOf(b);
        // Remainder and Euclidean modulus stay defined even for MIN/-1.
        // BigInteger.abs also handles the magnitude of the minimum divisor.
        assertEquals(dividend.remainder(divisor).longValueExact(), StrictIntegerArithmetic.rem(a, b));
        assertEquals(dividend.mod(divisor.abs()).longValueExact(), StrictIntegerArithmetic.mod(a, b));
        BigInteger quotient = dividend.divide(divisor);
        if (quotient.bitLength() > 63) {
            assertTrue(assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.div(a, b))
                    .getMessage().contains("ORES_I64_OVERFLOW"));
        } else {
            assertEquals(quotient.longValueExact(), StrictIntegerArithmetic.div(a, b));
        }
    }

    @Test void checkedNumericFaultsAreTypedOrdinaryGuestErrors() throws Exception {
        String[][] cases = {
            {"a / b", "7", "0", "DivisionByZeroError"},
            {"a / b", "-9223372036854775808", "-1", "ArithmeticOverflowError"},
            {"a + b", "9223372036854775807", "1", "ArithmeticOverflowError"},
            {"a - b", "-9223372036854775808", "1", "ArithmeticOverflowError"},
            {"a * b", "3037000500", "3037000500", "ArithmeticOverflowError"},
            {"-a", "-9223372036854775808", "0", "ArithmeticOverflowError"},
            {"Integer.rem(a, b)", "7", "0", "DivisionByZeroError"},
            {"Integer.mod(a, b)", "7", "0", "DivisionByZeroError"}
        };
        for (String spelling : new String[] {"trap", "trap!"}) {
            for (String[] c : cases) {
                String source = """
                        // oreslang:checked-i64-v1
                        fnc checked(int a, int b): int { return %s; }
                        pub fnc main(): int {
                          val outcome = %s checked(%s, %s);
                          do match outcome over
                            on Ok(value) -> { return 1; }
                            on Err(error) -> {
                              if error is type %s then return 42; fi
                              return 2;
                            }
                          end
                          return 3;
                        }
                        """.formatted(c[0], spelling, c[1], c[2], c[3]);
                assertEquals(42.0, run(source, "typed-fault.ores"), c[0] + " " + spelling);
            }
        }
    }

    @Test void ordinaryNumericFaultIdentityDoesNotReclassifyHostArithmetic() {
        var zero = dev.oreslang.runtime.GuestErrorValue.divisionByZero();
        var overflow = dev.oreslang.runtime.GuestErrorValue.arithmeticOverflow();
        var signal = dev.oreslang.runtime.GuestOrdinaryThrow.signal(zero);
        assertSame(zero, signal.error());
        assertNotSame(zero, dev.oreslang.runtime.GuestErrorValue.divisionByZero());
        assertEquals(65538, zero.abiCode());
        assertEquals(65537, overflow.abiCode());
        assertEquals("DivisionByZeroError", zero.nominalType());
        assertEquals("ArithmeticOverflowError", overflow.nominalType());
        assertTrue(dev.oreslang.runtime.TrapFailurePolicy.isTrappable(signal, IllegalStateException.class));
        assertFalse(dev.oreslang.runtime.TrapFailurePolicy.isTrappable(
                new ArithmeticException("ORES_I64_DIVISION_BY_ZERO"), IllegalStateException.class));
        assertFalse(dev.oreslang.runtime.TrapFailurePolicy.isTrappable(
                new RuntimeException(signal), IllegalStateException.class));
        signal.addSuppressed(new java.util.concurrent.CancellationException());
        assertFalse(dev.oreslang.runtime.TrapFailurePolicy.isTrappable(signal, IllegalStateException.class));
    }

    @Test void exactNumericOrderingMatchesIndependentOracle() {
        long[] ints = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -9007199254740993L, -1, 0, 1,
                9007199254740992L, 9007199254740993L, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        double[] doubles = {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                -0x1.0p63, 0x1.0p63, Math.nextDown(0x1.0p63), -0.0, 0.0,
                -0.5, 0.5, Double.MIN_VALUE, -Double.MIN_VALUE, 9007199254740992d,
                Math.nextUp(9007199254740992d)};
        for (long a : ints) for (double b : doubles) checkOrder(a, b);
        Random random = new Random(8187);
        for (int i = 0; i < 100000; i++) {
            long a = random.nextLong();
            checkOrder(a, Double.longBitsToDouble(random.nextLong()));
            checkOrder(a, (double) a);
        }
        assertEquals(-1, dev.oreslang.types.NumericValueOrdering.compare(new java.math.BigDecimal("0.1"), 0.1d));
        assertEquals(0, dev.oreslang.types.NumericValueOrdering.compare(-0.0, +0.0));
        for (String op : new String[]{"<", "<=", ">", ">="})
            assertFalse(dev.oreslang.types.NumericValueOrdering.ordered(Double.NaN, 1.0, op));
    }

    private static void checkOrder(long a, double b) {
        int expected = Double.isNaN(b) ? dev.oreslang.types.NumericValueOrdering.UNORDERED
                : b == Double.POSITIVE_INFINITY ? -1 : b == Double.NEGATIVE_INFINITY ? 1
                : Integer.signum(java.math.BigDecimal.valueOf(a).compareTo(new java.math.BigDecimal(b)));
        assertEquals(expected, dev.oreslang.types.NumericValueOrdering.compare(a, b));
        assertEquals(expected == dev.oreslang.types.NumericValueOrdering.UNORDERED ? expected : -expected,
                dev.oreslang.types.NumericValueOrdering.compare(b, a));
    }

    @Test void guestOrderingPreservesIntegerPrecisionAndIeeeUnorderedValues() throws Exception {
        assertEquals(42.0, run("""
                pub fnc main(): int {
                  val int high = 9007199254740993;
                  val int low = 9007199254740992;
                  val float nan = 0.0 / 0.0;
                  if nan < 1.0 || nan <= 1.0 || nan > 1.0 || nan >= 1.0 then return 1; fi
                  if -0.0 < 0.0 || -0.0 > 0.0 then return 2; fi
                  if high > low then return 42; fi
                  return 3;
                }
                """, "ordering.atto"));
    }
}
