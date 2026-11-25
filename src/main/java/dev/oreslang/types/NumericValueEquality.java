package dev.oreslang.types;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Exact numeric equality shared by static pattern proofs and guest values. */
public final class NumericValueEquality {
    private NumericValueEquality() { }

    public static boolean equals(Number left, Number right) {
        if (left instanceof BigDecimal || right instanceof BigDecimal
                || left instanceof BigInteger || right instanceof BigInteger) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
            } catch (NumberFormatException ignored) {
                // BigDecimal cannot represent NaN or infinity. Never fall back
                // to double: huge BigInteger/BigDecimal can round to infinity.
                return false;
            }
        }
        boolean integralLeft = isIntegral(left);
        boolean integralRight = isIntegral(right);
        if (integralLeft && integralRight) return left.longValue() == right.longValue();
        if (integralLeft != integralRight) {
            Number integer = integralLeft ? left : right;
            Number floating = integralLeft ? right : left;
            if (!Double.isFinite(floating.doubleValue())) return false;
            return BigDecimal.valueOf(integer.longValue())
                    .compareTo(BigDecimal.valueOf(floating.doubleValue())) == 0;
        }
        return left.doubleValue() == right.doubleValue();
    }

    private static boolean isIntegral(Number value) {
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long;
    }
}
