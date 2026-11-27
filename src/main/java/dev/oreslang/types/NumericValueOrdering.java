package dev.oreslang.types;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Exact comparison of already admitted numeric carriers. */
public final class NumericValueOrdering {
    private NumericValueOrdering() {}
    public static final int UNORDERED = 2;

    private static boolean integer(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte;
    }
    private static boolean floating(Number n) {
        return n instanceof Double || n instanceof Float;
    }
    private static void requireCarrier(Number n) {
        if (!integer(n) && !floating(n) && !(n instanceof BigInteger) && !(n instanceof BigDecimal))
            throw new IllegalArgumentException("unadmitted numeric carrier");
    }
    /** No allocation or binary64 rounding of the integer on this path. */
    private static int integerFloat(long a, double b) {
        if (Double.isNaN(b)) return UNORDERED;
        if (b >= 0x1.0p63) return -1;
        if (b < -0x1.0p63) return 1;
        long integral = (long) b;
        int cmp = Long.compare(a, integral);
        if (cmp != 0) return cmp;
        // If the integer agrees with the truncated value, only a fractional
        // part can distinguish them. All integers in binary64's fractional
        // range are exactly representable as double.
        return b > (double) integral ? -1 : b < (double) integral ? 1 : 0;
    }
    public static int compare(Number a, Number b) {
        requireCarrier(a);
        requireCarrier(b);
        if (integer(a) && integer(b)) return Long.compare(a.longValue(), b.longValue());
        if (integer(a) && floating(b)) return integerFloat(a.longValue(), b.doubleValue());
        if (floating(a) && integer(b)) {
            int result = integerFloat(b.longValue(), a.doubleValue());
            return result == UNORDERED ? UNORDERED : -result;
        }
        if (floating(a) && floating(b)) {
            double x = a.doubleValue(), y = b.doubleValue();
            if (Double.isNaN(x) || Double.isNaN(y)) return UNORDERED;
            return x < y ? -1 : x > y ? 1 : 0;
        }
        if (floating(a)) {
            double x = a.doubleValue();
            if (Double.isNaN(x)) return UNORDERED;
            if (Double.isInfinite(x)) return x > 0 ? 1 : -1;
        }
        if (floating(b)) {
            double y = b.doubleValue();
            if (Double.isNaN(y)) return UNORDERED;
            if (Double.isInfinite(y)) return y > 0 ? -1 : 1;
        }
        return Integer.signum(exactDecimal(a).compareTo(exactDecimal(b)));
    }
    private static BigDecimal exactDecimal(Number n) {
        if (n instanceof BigDecimal decimal) return decimal;
        if (n instanceof BigInteger integer) return new BigDecimal(integer);
        if (integer(n)) return BigDecimal.valueOf(n.longValue());
        return new BigDecimal(n.doubleValue());
    }
    public static boolean ordered(Number a, Number b, String op) {
        int c = compare(a, b);
        if (c == UNORDERED) return false;
        return switch (op) {
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            default -> throw new IllegalArgumentException("not an ordering operator");
        };
    }
}
