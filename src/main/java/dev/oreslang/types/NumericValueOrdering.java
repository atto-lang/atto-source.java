package dev.oreslang.types;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Exact ordering of already-admitted numeric carriers for guest comparison.
 *
 * <p>Null means IEEE-754 unordered (either operand is NaN), never an error or
 * a value that can accidentally satisfy {@code <}/{@code >}. Signed zeros
 * compare equal. Mixed integer/binary64 ordering is exact: converting a long
 * or bigint to double before comparing can collapse distinct values above
 * 2^53. Host Number implementations outside these carriers fail closed.
 *
 * <p>This is a semantic helper, NOT source-level admission of bigint,
 * decimal, f32 or implicit mixed-type conversions; those require the static
 * checker and explicit numeric representation contracts.
 */
public final class NumericValueOrdering {
    private NumericValueOrdering() {}

    public static Integer compare(Number left, Number right) {
        Objects.requireNonNull(left, "numeric left");
        Objects.requireNonNull(right, "numeric right");

        boolean leftBinary = isBinaryFloat(left);
        boolean rightBinary = isBinaryFloat(right);
        if ((leftBinary && Double.isNaN(left.doubleValue()))
                || (rightBinary && Double.isNaN(right.doubleValue()))) {
            return null;
        }

        // Process infinities only for actual binary-float values. A very
        // large (finite) bigint must never be mistaken for infinity merely
        // because bigint.doubleValue() would overflow.
        if (leftBinary && Double.isInfinite(left.doubleValue())) {
            return rightBinary && Double.isInfinite(right.doubleValue())
                    ? Integer.valueOf(Double.compare(left.doubleValue(), right.doubleValue()))
                    : Integer.valueOf(left.doubleValue() > 0 ? 1 : -1);
        }
        if (rightBinary && Double.isInfinite(right.doubleValue())) {
            return Integer.valueOf(right.doubleValue() > 0 ? -1 : 1);
        }

        if (isMachineInteger(left) && isMachineInteger(right)) {
            return Integer.valueOf(Long.compare(left.longValue(), right.longValue()));
        }

        // Binary float values are converted from their *exact* IEEE-754
        // values, not BigDecimal.valueOf(double)'s shortest decimal spelling.
        // This matters for comparisons against exact decimal or large ints.
        return Integer.valueOf(exactDecimal(left).compareTo(exactDecimal(right)));
    }

    private static boolean isMachineInteger(Number value) {
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long;
    }

    private static boolean isBinaryFloat(Number value) {
        return value instanceof Float || value instanceof Double;
    }

    private static BigDecimal exactDecimal(Number value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof BigInteger integer) return new BigDecimal(integer);
        if (isMachineInteger(value)) return BigDecimal.valueOf(value.longValue());
        if (isBinaryFloat(value)) return new BigDecimal(value.doubleValue());
        throw new IllegalArgumentException(
                "unadmitted numeric carrier: " + value.getClass().getName());
    }
}
