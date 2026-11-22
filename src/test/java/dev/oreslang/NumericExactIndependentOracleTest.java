package dev.oreslang;

import dev.oreslang.types.NumericValueEquality;
import dev.oreslang.types.NumericValueOrdering;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent exact rational oracle for the two production numerical helpers.
 * Reconstructs IEEE-754 Float/Double from raw mantissa/exponent bits rather
 * than using BigDecimal(double), which is used by the implementation.
 *
 * This test executes the real Java helper classes; it is NOT proof of Atto
 * guest compilation, tree-shaker reachability or full guest Native Image.
 */
final class NumericExactIndependentOracleTest {
    private record Rational(BigInteger numerator, BigInteger denominator) {
        Rational {
            if (denominator.signum() <= 0) throw new AssertionError("invalid denominator");
        }
        int compareTo(Rational other) {
            return numerator.multiply(other.denominator)
                    .compareTo(other.numerator.multiply(denominator));
        }
    }

    private static Rational scaledBinary(BigInteger integer, int exponent) {
        return exponent >= 0
                ? new Rational(integer.shiftLeft(exponent), BigInteger.ONE)
                : new Rational(integer, BigInteger.ONE.shiftLeft(-exponent));
    }

    private static Rational exact(Number value) {
        if (value instanceof BigInteger big) return new Rational(big, BigInteger.ONE);
        if (value instanceof BigDecimal decimal) {
            int scale = decimal.scale();
            return scale >= 0
                    ? new Rational(decimal.unscaledValue(), BigInteger.TEN.pow(scale))
                    : new Rational(decimal.unscaledValue()
                            .multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
        }
        if (value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            return new Rational(BigInteger.valueOf(value.longValue()), BigInteger.ONE);
        }
        if (value instanceof Double binary) {
            long bits = Double.doubleToRawLongBits(binary);
            long mantissa = bits & ((1L << 52) - 1);
            int rawExponent = (int) ((bits >>> 52) & 0x7ffL);
            if (rawExponent == 0x7ff) throw new AssertionError("nonfinite exact rational");
            long coefficient = rawExponent == 0 ? mantissa : mantissa | (1L << 52);
            BigInteger signed = BigInteger.valueOf(coefficient);
            if ((bits & Long.MIN_VALUE) != 0) signed = signed.negate();
            return scaledBinary(signed, rawExponent == 0 ? -1074 : rawExponent - 1023 - 52);
        }
        if (value instanceof Float binary) {
            int bits = Float.floatToRawIntBits(binary);
            int mantissa = bits & ((1 << 23) - 1);
            int rawExponent = (bits >>> 23) & 0xff;
            if (rawExponent == 0xff) throw new AssertionError("nonfinite exact rational");
            int coefficient = rawExponent == 0 ? mantissa : mantissa | (1 << 23);
            BigInteger signed = BigInteger.valueOf(coefficient);
            if ((bits & 0x80000000) != 0) signed = signed.negate();
            return scaledBinary(signed, rawExponent == 0 ? -149 : rawExponent - 127 - 23);
        }
        throw new AssertionError("unsupported oracle Number");
    }

    private static Integer reference(Number left, Number right) {
        boolean floatingLeft = left instanceof Double || left instanceof Float;
        boolean floatingRight = right instanceof Double || right instanceof Float;
        double l = left.doubleValue(), r = right.doubleValue();
        if ((floatingLeft && Double.isNaN(l))
                || (floatingRight && Double.isNaN(r))) return null;
        if (floatingLeft && Double.isInfinite(l)) {
            if (floatingRight && Double.isInfinite(r)) {
                return Integer.signum(Double.compare(l, r));
            }
            return l > 0 ? 1 : -1;
        }
        if (floatingRight && Double.isInfinite(r)) return r > 0 ? -1 : 1;
        return Integer.signum(exact(left).compareTo(exact(right)));
    }

    private static Number next(Random random) {
        return switch (random.nextInt(11)) {
            case 0 -> random.nextLong();
            case 1 -> random.nextInt();
            case 2 -> (short) random.nextInt();
            case 3 -> (byte) random.nextInt();
            case 4 -> Double.longBitsToDouble(random.nextLong());
            case 5 -> Float.intBitsToFloat(random.nextInt());
            case 6 -> new BigInteger(100 + random.nextInt(1000), random)
                    .multiply(random.nextBoolean() ? BigInteger.ONE : BigInteger.ONE.negate());
            case 7 -> new BigDecimal(BigInteger.valueOf(random.nextLong()), random.nextInt(160) - 80);
            case 8 -> random.nextBoolean() ? Double.NaN : Double.POSITIVE_INFINITY;
            case 9 -> random.nextBoolean() ? Float.NEGATIVE_INFINITY : Float.NaN;
            default -> switch (random.nextInt(5)) {
                case 0 -> Double.MIN_VALUE;
                case 1 -> -0.0d;
                case 2 -> 9007199254740993L;
                case 3 -> Long.MIN_VALUE;
                default -> new BigDecimal("0.1");
            };
        };
    }

    @Test
    void deterministicIndependentRationalOracleAndAntisymmetry() {
        Random random = new Random(0x20261121L);
        for (int i = 0; i < 120_000; i++) {
            Number left = next(random), right = next(random);
            Integer reference = reference(left, right);
            Integer actual = NumericValueOrdering.compare(left, right);
            if ((reference == null) != (actual == null)
                    || reference != null && Integer.signum(actual) != reference) {
                fail("order mismatch at pair " + i + ": " + left + ", " + right
                        + ", expected " + reference + " but got " + actual);
            }
            if (NumericValueEquality.equals(left, right)
                    != (reference != null && reference == 0)) {
                fail("equality mismatch at pair " + i);
            }
            Integer backward = NumericValueOrdering.compare(right, left);
            if ((actual == null) != (backward == null)
                    || actual != null && Integer.signum(actual) != -Integer.signum(backward)) {
                fail("antisymmetry mismatch at pair " + i);
            }
        }
        assertEquals(0, NumericValueOrdering.compare(-0.0d, 0L));
        assertNull(NumericValueOrdering.compare(Double.NaN, Float.NaN));
        assertTrue(NumericValueOrdering.compare(Long.MAX_VALUE, 9223372036854775808.0d) < 0);
        assertFalse(NumericValueEquality.equals(new BigDecimal("0.1"), 0.1d));
        assertThrows(IllegalArgumentException.class,
                () -> NumericValueOrdering.compare(new AtomicLong(1), Double.POSITIVE_INFINITY));
    }
}
