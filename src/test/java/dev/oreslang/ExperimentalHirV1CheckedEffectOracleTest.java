package dev.oreslang;

import dev.oreslang.runtime.CheckedI64OverflowException;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent BigInteger arithmetic oracle for the checked host-signal ABI.
 * The real guest-evaluator tests live in ExperimentalHirV1CheckedArithmetic*
 * and must execute separately; this test does not certify Graal/Truffle JIT.
 */
final class ExperimentalHirV1CheckedEffectOracleTest {
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private static boolean outOfRange(BigInteger value) {
        return value.compareTo(MIN) < 0 || value.compareTo(MAX) > 0;
    }

    private static void checkPair(long left, long right, char operator) {
        BigInteger a = BigInteger.valueOf(left), b = BigInteger.valueOf(right);
        BigInteger reference = switch (operator) {
            case '+' -> a.add(b);
            case '-' -> a.subtract(b);
            case '*' -> a.multiply(b);
            default -> throw new AssertionError("test oracle operator");
        };
        if (!outOfRange(reference)) {
            long actual = assertDoesNotThrow(() -> switch (operator) {
                case '+' -> Math.addExact(left, right);
                case '-' -> Math.subtractExact(left, right);
                case '*' -> Math.multiplyExact(left, right);
                default -> throw new AssertionError();
            });
            assertEquals(reference, BigInteger.valueOf(actual));
        } else {
            assertThrows(ArithmeticException.class, () -> {
                switch (operator) {
                    case '+' -> Math.addExact(left, right);
                    case '-' -> Math.subtractExact(left, right);
                    case '*' -> Math.multiplyExact(left, right);
                    default -> throw new AssertionError();
                }
            });
            CheckedI64OverflowException signal =
                    CheckedI64OverflowException.forOperator(operator);
            assertEquals(0x494F0001, signal.effectCode());
            assertEquals("ORES_I64_OVERFLOW", signal.errorCode());
            assertEquals("ArithmeticOverflowError", signal.guestErrorName());
            assertEquals("Throw", signal.effectKind());
            assertEquals(operator == '+' ? 1 : operator == '-' ? 2 : 3,
                    signal.operationTag());
            assertNull(signal.getCause(), "do not retain host ArithmeticException");
        }
    }

    private static void checkNegation(long operand) {
        BigInteger reference = BigInteger.valueOf(operand).negate();
        if (outOfRange(reference)) {
            assertThrows(ArithmeticException.class, () -> Math.negateExact(operand));
            CheckedI64OverflowException signal =
                    CheckedI64OverflowException.forNegation();
            assertEquals(4, signal.operationTag());
            assertEquals(0x494F0001, signal.effectCode());
            assertNull(signal.getCause());
        } else {
            assertEquals(reference.longValueExact(), Math.negateExact(operand));
        }
    }

    @Test
    void boundaryAndDeterministicRandomizedArithmeticAgreeWithMathematicalOracle() {
        long[] edges = {Long.MIN_VALUE, Long.MIN_VALUE + 1,
                -1_000_000_000_000L, -43, -2, -1, 0, 1, 2, 43,
                1_000_000_000_000L, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        for (long a : edges) {
            checkNegation(a);
            for (long b : edges) {
                checkPair(a, b, '+');
                checkPair(a, b, '-');
                checkPair(a, b, '*');
            }
        }
        Random rng = new Random(20261015L);
        for (int i = 0; i < 8192; ++i) {
            long a = rng.nextLong(), b = rng.nextLong();
            checkNegation(a);
            checkPair(a, b, '+');
            checkPair(a, b, '-');
            checkPair(a, b, '*');
        }
    }
}
