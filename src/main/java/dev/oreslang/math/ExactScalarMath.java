package dev.oreslang.math;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Objects;

/**
 * Exact arithmetic boundary for values already represented as Java BigInteger
 * or BigDecimal. This is not yet guest BigInt/Decimal source-level admission.
 *
 * <p>The Java guest evaluator must not silently convert arbitrary-precision
 * numbers to Double, even when the current parser cannot construct these types
 * directly. All operations have bounded digit/scale admission and no implicit
 * mixing with binary floating point. Decimal division without a context only
 * accepts exact terminating quotients.
 */
public final class ExactScalarMath {
    private static final int MAX_DIGITS = 4096;
    private static final int MAX_ABSOLUTE_SCALE = 16384;

    private ExactScalarMath() { }

    public static Number evaluate(Number left, Number right, char operator) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!(left instanceof BigDecimal || right instanceof BigDecimal
                || left instanceof BigInteger || right instanceof BigInteger)) {
            throw new IllegalArgumentException("exact math needs arbitrary-precision operands");
        }
        if (left instanceof Float || left instanceof Double
                || right instanceof Float || right instanceof Double) {
            throw new IllegalArgumentException(
                    "implicit BigInt/Decimal and binary-float arithmetic loses precision");
        }

        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            BigDecimal a = toDecimal(left);
            BigDecimal b = toDecimal(right);
            validate(a);
            validate(b);
            BigDecimal result = switch (operator) {
                case '+' -> a.add(b);
                case '-' -> a.subtract(b);
                case '*' -> a.multiply(b);
                case '%' -> a.remainder(b);
                case '/' -> divideExactly(a, b);
                default -> throw new IllegalArgumentException("unsupported exact decimal operator");
            };
            validate(result);
            return result;
        }

        BigInteger a = toBigInteger(left);
        BigInteger b = toBigInteger(right);
        validate(a);
        validate(b);
        BigInteger result = switch (operator) {
            case '+' -> a.add(b);
            case '-' -> a.subtract(b);
            case '*' -> a.multiply(b);
            case '%' -> a.remainder(b);
            case '/' -> throw new IllegalArgumentException(
                    "BigInt division needs explicit result domain; use exact decimal/rational API");
            default -> throw new IllegalArgumentException("unsupported exact integer operator");
        };
        validate(result);
        return result;
    }

    /**
     * Explicit caller-selected rounding context for nonterminating division.
     * No mutable process-global precision or rounding state is used.
     */
    public static BigDecimal divide(
            BigDecimal numerator, BigDecimal denominator, MathContext context) {
        Objects.requireNonNull(context, "context");
        validate(Objects.requireNonNull(numerator, "numerator"));
        validate(Objects.requireNonNull(denominator, "denominator"));
        if (context.getPrecision() <= 0 || context.getPrecision() > MAX_DIGITS) {
            throw new IllegalArgumentException("decimal division context precision out of budget");
        }
        // RoundingMode.UNNECESSARY already rejects any inexact quotient
        // with ArithmeticException; no special global rounding state.
        return checked(numerator.divide(denominator, context));
    }

    private static BigDecimal divideExactly(BigDecimal a, BigDecimal b) {
        try {
            return a.divide(b);
        } catch (ArithmeticException error) {
            // Keep division by zero distinct from inexact decimal division.
            if (b.signum() == 0) throw error;
            throw new IllegalArgumentException(
                    "nonterminating decimal division requires an explicit MathContext", error);
        }
    }

    private static BigInteger toBigInteger(Number number) {
        if (number instanceof BigInteger v) return v;
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return BigInteger.valueOf(number.longValue());
        }
        throw new IllegalArgumentException("numeric value is not an exact integer");
    }

    private static BigDecimal toDecimal(Number number) {
        if (number instanceof BigDecimal v) return v;
        BigInteger value = toBigInteger(number);
        validate(value); // reject enormous host integers before decimal conversion
        return new BigDecimal(value);
    }

    private static BigDecimal checked(BigDecimal result) {
        validate(result);
        return result;
    }

    private static void validate(BigInteger value) {
        // Approximate base-10 size from bits with a conservative bound, then
        // check exact decimal digit count before admitting the scalar.
        if (value.bitLength() > 4 * MAX_DIGITS
                || value.abs().toString().length() > MAX_DIGITS) {
            throw new IllegalArgumentException("BigInt digit budget exceeded");
        }
    }

    private static void validate(BigDecimal value) {
        if (value.precision() > MAX_DIGITS
                || Math.abs((long) value.scale()) > MAX_ABSOLUTE_SCALE) {
            throw new IllegalArgumentException("Decimal precision or exponent budget exceeded");
        }
    }
}
