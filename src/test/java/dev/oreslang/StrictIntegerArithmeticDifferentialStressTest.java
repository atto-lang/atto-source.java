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

    @Test
    void fullWidthPowerAndZeroOneIdentitiesMatchIndependentOracle() {
        Random random = new Random(0x5A17A07B00L);
        BigInteger min = BigInteger.valueOf(Long.MIN_VALUE);
        BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
        for (int i = 0; i < 20_000; i++) {
            long base = random.nextLong();
            int exponent = i & 7;
            BigInteger expected = BigInteger.valueOf(base).pow(exponent);
            if (expected.compareTo(min) < 0 || expected.compareTo(max) > 0) {
                requireThrows(ArithmeticException.class,
                        () -> StrictIntegerArithmetic.pow(base, exponent),
                        "wide power overflow");
            } else {
                require(StrictIntegerArithmetic.pow(base, exponent) == expected.longValueExact(),
                        "wide power mismatch: " + base + ", " + exponent);
            }
            require(StrictIntegerArithmetic.pow(base, 0) == 1L,
                    "wide power identity zero: " + base);
            require(StrictIntegerArithmetic.pow(base, 1) == base,
                    "wide power identity one: " + base);
        }
    }

    @Test
    void bitwiseXorMetamorphicPropertiesCoverFullWidthValues() {
        Random random = new Random(0xB17A16E42L);
        for (int i = 0; i < 5_000; i++) {
            long left = random.nextLong();
            long right = random.nextLong();
            long combined = StrictIntegerArithmetic.bitXor(left, right);
            require(combined == StrictIntegerArithmetic.bitXor(right, left),
                    "xor commutativity");
            require(StrictIntegerArithmetic.bitXor(left, left) == 0L,
                    "xor self cancellation");
            require(StrictIntegerArithmetic.bitXor(left, 0L) == left,
                    "xor zero identity");
            require(StrictIntegerArithmetic.bitXor(combined, right) == left,
                    "xor inverse identity");
        }
    }

    /** Walk every representable power-of-two boundary, including negative edges. */
    @Test
    void signedDivisionAcrossEveryBinaryBoundaryMatchesIndependentOracle() {
        for (int bit = 0; bit <= 62; bit++) {
            long power = 1L << bit;
            long[] cases = {
                    Long.MIN_VALUE, Long.MIN_VALUE + 1,
                    Long.MAX_VALUE - 1, Long.MAX_VALUE,
                    power - 1, power, power + 1,
                    -power - 1, -power, -power + 1,
                    -3, -1, 0, 1, 3
            };
            for (long dividend : cases) {
                for (long divisor : cases) {
                    comparePair(dividend, divisor);
                }
            }
        }
    }

    /** Algebraic laws independently check signed division, not only expected vectors. */
    @Test
    void signedDivisionAndModulusKeepAlgebraicCongruenceUnderFullWidthStress() {
        Random random = new Random(0x4C4157537EEDL);
        for (int i = 0; i < 15_000; i++) {
            long dividend = i % 3 == 0 ? EXTREMES[i % EXTREMES.length] : random.nextLong();
            long divisor = i % 3 == 1 ? EXTREMES[i % EXTREMES.length] : random.nextLong();
            if (divisor == 0) divisor = Long.MIN_VALUE;
            long remainder = StrictIntegerArithmetic.rem(dividend, divisor);
            long modulus = StrictIntegerArithmetic.mod(dividend, divisor);
            BigInteger a = BigInteger.valueOf(dividend);
            BigInteger b = BigInteger.valueOf(divisor);
            BigInteger r = BigInteger.valueOf(remainder);
            BigInteger m = BigInteger.valueOf(modulus);
            require(b.multiply(a.divide(b)).add(r).equals(a), "rem quotient reconstruction");
            require(r.signum() == 0 || r.signum() == a.signum(), "rem dividend sign");
            require(m.signum() >= 0 && m.compareTo(b.abs()) < 0, "euclidean mod bound");
            require(m.subtract(r).mod(b.abs()).signum() == 0, "rem/mod congruence");
            require(StrictIntegerArithmetic.rem(dividend, -1) == 0,
                    "division by negative unit must have zero remainder");
            // Explicitly ensure min / -1 remains handled without BigInteger long overflow.
            if (dividend == Long.MIN_VALUE) {
                require(StrictIntegerArithmetic.mod(dividend, -1) == 0, "min modulus -1");
            }
            require(a.subtract(m).mod(b.abs()).signum() == 0,
                    "dividend/mod congruence");
        }
    }

    public static void main(String[] args) {
        StrictIntegerArithmeticDifferentialStressTest tests =
                new StrictIntegerArithmeticDifferentialStressTest();
        tests.exhaustivelyChecksSmallSignedValuesAndExtremeCrossProduct();
        tests.deterministicFullWidthFuzzMatchesIndependentBigInteger();
        tests.boundedPowersMatchBigIntegerAndRejectOverflow();
        tests.extremeExponentAndFailureBoundariesAreExplicit();
        tests.fullWidthPowerAndZeroOneIdentitiesMatchIndependentOracle();
        tests.bitwiseXorMetamorphicPropertiesCoverFullWidthValues();
        tests.signedDivisionAcrossEveryBinaryBoundaryMatchesIndependentOracle();
        tests.signedDivisionAndModulusKeepAlgebraicCongruenceUnderFullWidthStress();
        System.out.println("STRICT_I64_DEEP_PROBE|PASS|checks=" + checks);
    }
}
