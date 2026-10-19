package dev.oreslang;

import dev.oreslang.runtime.CheckedI64Arithmetic;
import dev.oreslang.runtime.CheckedI64DivisionByZeroException;
import dev.oreslang.runtime.CheckedI64OverflowException;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-JVM reference for production helper used by linked guest runtime.
 * Oracle BigInteger calculations must not reuse the implementation's
 * Math.*Exact or long wrapping logic when deciding expectations.
 *
 * This is not Graal guest or cross-backend parity evidence.
 */
final class CheckedI64ArithmeticOracleTest {
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final char[] OPS = {'+', '-', '*', '%'};

    private static void assertPair(char operator, long left, long right) {
        BigInteger l = BigInteger.valueOf(left), r = BigInteger.valueOf(right);
        if (operator == '%' && right == 0L) {
            CheckedI64DivisionByZeroException zero = assertThrows(
                    CheckedI64DivisionByZeroException.class,
                    () -> CheckedI64Arithmetic.binary(operator, left, right));
            assertEquals("ORES_I64_DIVISION_BY_ZERO", zero.errorCode());
            assertEquals("DivisionByZeroError", zero.guestErrorName());
            assertEquals("Throw", zero.effectKind());
            assertFalse(zero.getMessage().contains("/ by zero"),
                    "checked failures must not leak JVM exception wording");
            return;
        }
        BigInteger exact = switch (operator) {
            case '+' -> l.add(r);
            case '-' -> l.subtract(r);
            case '*' -> l.multiply(r);
            case '%' -> l.remainder(r);
            default -> throw new AssertionError("unknown test operator");
        };
        if (exact.compareTo(MIN) < 0 || exact.compareTo(MAX) > 0) {
            CheckedI64OverflowException overflow = assertThrows(
                    CheckedI64OverflowException.class,
                    () -> CheckedI64Arithmetic.binary(operator, left, right));
            assertEquals("ORES_I64_OVERFLOW", overflow.errorCode());
            assertEquals(operator == '+' ? 1 : operator == '-' ? 2 : 3,
                    overflow.operationTag());
            assertEquals(operator == '+' ? "add"
                    : operator == '-' ? "subtract" : "multiply",
                    overflow.operation());
            assertEquals("ArithmeticOverflowError", overflow.guestErrorName());
            assertEquals("Throw", overflow.effectKind());
            assertEquals(0x494F0001, overflow.effectCode());
        } else {
            assertEquals(exact.longValueExact(),
                    CheckedI64Arithmetic.binary(operator, left, right),
                    "operator=" + operator + " lhs=" + left + " rhs=" + right);
        }
    }

    @Test
    void boundaryPairsCoverAllOperatorsAndZeroDivisor() {
        long[] points = {
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -3037000500L,
            -3037000499L, -43L, -1L, 0L, 1L, 43L,
            3037000499L, 3037000500L, Long.MAX_VALUE - 1,
            Long.MAX_VALUE
        };
        for (long left : points) for (long right : points) {
            for (char operator : OPS) assertPair(operator, left, right);
        }
        assertEquals(0L, CheckedI64Arithmetic.binary('%', Long.MIN_VALUE, -1L));
        assertEquals(-1L, CheckedI64Arithmetic.binary('%', Long.MIN_VALUE, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> CheckedI64Arithmetic.binary('/', 43L, 1L));
    }

    @Test
    void seededRandomPairsDifferentialAgainstBigInteger() {
        Random random = new Random(0x5845d25eL);
        for (int index = 0; index < 4096; index++) {
            long left = random.nextLong();
            long right = index % 97 == 0 ? 0L : random.nextLong();
            for (char operator : OPS) assertPair(operator, left, right);
        }
    }

    @Test
    void boundedOperandsExerciseSuccessfulProductsAndSignedRemainders() {
        Random random = new Random(0x5a4c13dbL);
        for (int index = 0; index < 4096; index++) {
            long left = random.nextInt(1_000_001) - 500_000L;
            long right = index % 47 == 0 ? 0L
                    : random.nextInt(1_000_001) - 500_000L;
            for (char operator : OPS) assertPair(operator, left, right);
        }
    }

    @Test
    void unaryNegationIsExactExceptAtSignedMinimum() {
        for (long value : new long[] {
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -43, -1, 0, 1, 43,
            Long.MAX_VALUE - 1, Long.MAX_VALUE
        }) {
            if (value == Long.MIN_VALUE) {
                CheckedI64OverflowException overflow = assertThrows(
                        CheckedI64OverflowException.class,
                        () -> CheckedI64Arithmetic.negate(value));
                assertEquals(4, overflow.operationTag());
                assertEquals("negate", overflow.operation());
                assertEquals("ArithmeticOverflowError", overflow.guestErrorName());
                assertEquals("Throw", overflow.effectKind());
                assertEquals(0x494F0001, overflow.effectCode());
            } else {
                assertEquals(BigInteger.valueOf(value).negate().longValueExact(),
                        CheckedI64Arithmetic.negate(value));
            }
        }
        Random random = new Random(0x459a113L);
        for (int i = 0; i < 4096; i++) {
            long value = random.nextLong();
            if (value != Long.MIN_VALUE) {
                assertEquals(BigInteger.valueOf(value).negate().longValueExact(),
                        CheckedI64Arithmetic.negate(value));
            }
        }
    }
}
