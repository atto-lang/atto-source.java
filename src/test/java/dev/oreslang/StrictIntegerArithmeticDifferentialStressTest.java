package dev.oreslang;

import dev.oreslang.runtime.StrictIntegerArithmetic;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Exact signed-i64 differential tests. This class is both a JUnit test and a
 * standalone, dependency-free arithmetic probe when a JUnit Test annotation
 * stub is provided in an isolated local build.
 *
 * No compiler, Truffle guest or backend parity result is inferred here.
 */
final class StrictIntegerArithmeticDifferentialStressTest {
    private static final long[] EXTREMES = {
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -(1L << 62), -257, -3, -2,
            -1, 0, 1, 2, 3, 257, 1L << 62, Long.MAX_VALUE - 1,
            Long.MAX_VALUE
    };
    private static long checks;

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void comparePair(long left, long right) {
        BigInteger a = BigInteger.valueOf(left);
        BigInteger b = BigInteger.valueOf(right);
        require(StrictIntegerArithmetic.bitXor(left, right) == a.xor(b).longValueExact(),
                "bit_xor mismatch: " + left + ", " + right);
        if (right == 0L) {
            requireThrows(ArithmeticException.class,
                    () -> StrictIntegerArithmetic.rem(left, right), "rem zero");
            requireThrows(ArithmeticException.class,
                    () -> StrictIntegerArithmetic.mod(left, right), "mod zero");
            return;
        }
        BigInteger truncating = a.remainder(b);
        BigInteger euclidean = truncating.signum() < 0
                ? truncating.add(b.abs()) : truncating;
        long actualRem = StrictIntegerArithmetic.rem(left, right);
        long actualMod = StrictIntegerArithmetic.mod(left, right);
        require(actualRem == truncating.longValueExact(),
                "rem mismatch: " + left + ", " + right);
        require(actualMod == euclidean.longValueExact(),
                "mod mismatch: " + left + ", " + right);
        require(BigInteger.valueOf(actualMod).signum() >= 0
                        && BigInteger.valueOf(actualMod).compareTo(b.abs()) < 0,
                "mod bound mismatch: " + left + ", " + right);
    }

    private static void requireThrows(Class<? extends Throwable> kind,
                                      Runnable runnable, String label) {
        try {
            runnable.run();
        } catch (Throwable failure) {
            require(kind.isInstance(failure),
                    label + " threw " + failure.getClass().getName());
            return;
        }
        require(false, label + " did not throw");
    }

    @Test
    void exhaustivelyChecksSmallSignedValuesAndExtremeCrossProduct() {
        for (long left = -128; left <= 128; left++) {
            for (long right = -128; right <= 128; right++) {
                comparePair(left, right);
            }
        }
        for (long left : EXTREMES) {
            for (long right : EXTREMES) {
                comparePair(left, right);
            }
        }
    }

    @Test
    void deterministicFullWidthFuzzMatchesIndependentBigInteger() {
        Random random = new Random(0x51A64C0DE42L);
        for (int i = 0; i < 25_000; i++) {
            long left = random.nextLong();
            long right = i % 7 == 0 ? EXTREMES[i % EXTREMES.length] : random.nextLong();
            comparePair(left, right);
        }
    }

    @Test
    void boundedPowersMatchBigIntegerAndRejectOverflow() {
        Random random = new Random(0x504F574552L);
        for (int i = 0; i < 10_000; i++) {
            long base = random.nextInt(513) - 256;
            int exponent = random.nextInt(90);
            BigInteger oracle = BigInteger.valueOf(base).pow(exponent);
            if (oracle.bitLength() > 63) {
                // A negative value with magnitude 2^63 is representable.
                if (oracle.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                        && oracle.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
                    require(StrictIntegerArithmetic.pow(base, exponent)
                            == oracle.longValueExact(), "negative exact-min power");
                } else {
                    requireThrows(ArithmeticException.class,
                            () -> StrictIntegerArithmetic.pow(base, exponent), "power overflow");
                }
            } else {
                require(StrictIntegerArithmetic.pow(base, exponent)
                        == oracle.longValueExact(), "power mismatch");
            }
        }
    }

    @Test
    void extremeExponentAndFailureBoundariesAreExplicit() {
        for (long exponent : new long[] {0, 1, 2, 31, 63, 64, 127, Long.MAX_VALUE}) {
            require(StrictIntegerArithmetic.pow(1, exponent) == 1, "one power");
            require(StrictIntegerArithmetic.pow(0, exponent)
                    == (exponent == 0 ? 1 : 0), "zero power");
            require(StrictIntegerArithmetic.pow(-1, exponent)
                    == ((exponent & 1L) == 0 ? 1 : -1), "minus-one power");
        }
        require(StrictIntegerArithmetic.pow(-2, 63) == Long.MIN_VALUE,
                "negative signed-min exact power");
        requireThrows(ArithmeticException.class,
                () -> StrictIntegerArithmetic.pow(2, 63), "positive signed-max overflow");
        requireThrows(ArithmeticException.class,
                () -> StrictIntegerArithmetic.pow(Long.MIN_VALUE, 2), "min square overflow");
        for (long exponent : new long[] {-1, -2, Long.MIN_VALUE}) {
            requireThrows(IllegalArgumentException.class,
                    () -> StrictIntegerArithmetic.pow(0, exponent), "zero negative power");
            requireThrows(IllegalArgumentException.class,
                    () -> StrictIntegerArithmetic.pow(1, exponent), "one negative power");
        }
    }

    public static void main(String[] args) {
        StrictIntegerArithmeticDifferentialStressTest tests =
                new StrictIntegerArithmeticDifferentialStressTest();
        tests.exhaustivelyChecksSmallSignedValuesAndExtremeCrossProduct();
        tests.deterministicFullWidthFuzzMatchesIndependentBigInteger();
        tests.boundedPowersMatchBigIntegerAndRejectOverflow();
        tests.extremeExponentAndFailureBoundariesAreExplicit();
        System.out.println("STRICT_I64_DEEP_PROBE|PASS|checks=" + checks);
    }
}
