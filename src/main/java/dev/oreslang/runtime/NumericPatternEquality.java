package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Exact equality for source numeric scalars. Never coerce an integral value
 * to binary64 to compare it with a float: integers above 2^53 can round to
 * a different integer and would make two distinct pattern arms overlap.
 *
 * Null means an unrecognized Number carrier: static overlap proofs must
 * fail closed rather than guessing its equality semantics.
 *
 * Floating-point values retain ordinary IEEE equality: NaN is unequal to
 * everything and signed zero compares equal.
 */
public final class NumericPatternEquality {
    private NumericPatternEquality() { }

    public static Boolean equal(Number left, Number right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        boolean leftIntegral = isIntegral(left);
        boolean rightIntegral = isIntegral(right);
        if (leftIntegral && rightIntegral) {
            return left.longValue() == right.longValue();
        }

        boolean leftFloat = isFloating(left);
        boolean rightFloat = isFloating(right);
        boolean leftWide = isWide(left);
        boolean rightWide = isWide(right);
        if (!(leftIntegral || leftFloat || leftWide)
                || !(rightIntegral || rightFloat || rightWide)) {
            return null;
        }

        if (leftFloat && rightFloat) {
            return left.doubleValue() == right.doubleValue();
        }

        // Floating NaN/infinity cannot equal any finite integral, BigInt,
        // or decimal value. These are not representable BigDecimal values.
        if ((leftFloat && !Double.isFinite(left.doubleValue()))
                || (rightFloat && !Double.isFinite(right.doubleValue()))) {
            return false;
        }

        // Constructing BigDecimal *from double* (not valueOf(double)) keeps
        // the precise binary fraction. Decimal-string roundtripping would
        // reintroduce false integer equality for large binary64 values.
        BigDecimal a = exactDecimal(left);
        BigDecimal b = exactDecimal(right);
        return a.compareTo(b) == 0;
    }

    private static BigDecimal exactDecimal(Number value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof BigInteger integer) return new BigDecimal(integer);
        if (isIntegral(value)) return BigDecimal.valueOf(value.longValue());
        return new BigDecimal(value.doubleValue());
    }

    private static boolean isIntegral(Number value) {
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long;
    }

    private static boolean isFloating(Number value) {
        return value instanceof Float || value instanceof Double;
    }

    private static boolean isWide(Number value) {
        return value instanceof BigInteger || value instanceof BigDecimal;
    }
}
