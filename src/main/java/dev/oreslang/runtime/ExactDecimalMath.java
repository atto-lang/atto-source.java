package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/**
 * Closed, deterministic exact-decimal numeric kernel for Oreslang-owned
 * std/decimal. The guest supplies precision and rounding on each operation;
 * there is no global MathContext or host-interoperability escape hatch.
 *
 * The kernel must be ported to LLVM and other targets before native parity.
 */
public final class ExactDecimalMath {
    public static final int MAX_PRECISION = 4_096;
    public static final int MAX_RESULT_PRECISION = 1_000_000;
    public static final long MAX_RESULT_SCALE = 200_000L;
    private ExactDecimalMath() {}

    private static MathContext context(long precision, String mode) {
        if (precision < 1 || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("decimal precision must be within 1..4096");
        }
        Objects.requireNonNull(mode, "rounding mode");
        if (!mode.matches("(UP|DOWN|CEILING|FLOOR|HALF_UP|HALF_DOWN|HALF_EVEN|UNNECESSARY)")) {
            throw new IllegalArgumentException("unknown exact decimal rounding mode: " + mode);
        }
        return new MathContext(Math.toIntExact(precision), RoundingMode.valueOf(mode));
    }

    private static BigDecimal checked(BigDecimal value) {
        if (value.precision() > MAX_RESULT_PRECISION
                || Math.abs((long) value.scale()) > MAX_RESULT_SCALE) {
            throw new ArithmeticException("decimal result exceeds precision/scale safety budget");
        }
        return value;
    }

    private static void preflight(BigDecimal value) {
        Objects.requireNonNull(value, "decimal operand");
        if (value.precision() > MAX_RESULT_PRECISION
                || Math.abs((long) value.scale()) > MAX_RESULT_SCALE) {
            throw new ArithmeticException("decimal operand exceeds precision/scale safety budget");
        }
    }

    public static BigDecimal divide(BigDecimal left, BigDecimal right, long precision, String mode) {
        preflight(left);
        preflight(right);
        if (right.signum() == 0) throw new ArithmeticException("decimal division by zero");
        MathContext context = context(precision, mode);
        return checked(left.divide(right, context));
    }

    public static BigDecimal round(BigDecimal value, long precision, String mode) {
        preflight(value);
        return checked(value.round(context(precision, mode)));
    }

    public static BigDecimal sqrt(BigDecimal value, long precision, String mode) {
        preflight(value);
        if (value.signum() < 0) throw new ArithmeticException("square root of negative decimal");
        return checked(value.sqrt(context(precision, mode)));
    }
}
