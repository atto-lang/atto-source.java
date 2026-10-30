package dev.oreslang.math;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Objects;

/**
 * Exact arithmetic boundary for Oreslang guest BigInt/Decimal carriers on the
 * Java backend. The carrier classes are backend details, not Java interop
 * exposed to guest code.
 *
 * <p>The guest evaluator must not silently convert arbitrary-precision
 * numbers to Double. All operations have bounded digit/scale admission and no implicit
 * mixing with binary floating point. Decimal division without a context only
 * accepts exact terminating quotients.
 */
public final class ExactScalarMath {
    private static final int MAX_DIGITS = 4096;
    private static final int MAX_ABSOLUTE_SCALE = 16384;

    private ExactScalarMath() { }

    /** Immutable quotient/remainder pair with truncation-toward-zero semantics. */
    public record IntegerDivision(BigInteger quotient, BigInteger remainder) {
        public IntegerDivision {
            Objects.requireNonNull(quotient, "quotient");
            Objects.requireNonNull(remainder, "remainder");
        }
    }

    /** Admit an exact integer carrier without silent truncation or unbounded limbs. */
    public static BigInteger admitInteger(Number value) {
        BigInteger result = toBigInteger(Objects.requireNonNull(value, "integer"));
        validate(result);
        return result;
    }

    /** Admit an exact decimal carrier, rejecting binary floats and excess scale. */
    public static BigDecimal admitDecimal(Number value) {
        BigDecimal result = toDecimal(Objects.requireNonNull(value, "decimal"));
        validate(result);
        return result;
    }

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
            preflightDecimal(a, b, operator);
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
        preflightInteger(a, b, operator);
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
     * Named BigInt division. Quotient truncates toward zero and remainder has
     * the dividend sign. The '/' operator remains a separate result-domain decision.
     */
    public static IntegerDivision divRem(BigInteger dividend, BigInteger divisor) {
        validate(Objects.requireNonNull(dividend, "dividend"));
        validate(Objects.requireNonNull(divisor, "divisor"));
        if (divisor.signum() == 0) {
            throw new ArithmeticException("BigInt division by zero");
        }
        BigInteger[] result = dividend.divideAndRemainder(divisor);
        validate(result[0]);
        validate(result[1]);
        return new IntegerDivision(result[0], result[1]);
    }

    public static BigInteger quotient(BigInteger dividend, BigInteger divisor) {
        return divRem(dividend, divisor).quotient();
    }

    public static BigInteger remainder(BigInteger dividend, BigInteger divisor) {
        return divRem(dividend, divisor).remainder();
    }

    /** Checked narrowing to the signed-64 Oreslang integer domain. */
    public static long toLongExact(BigInteger value) {
        validate(Objects.requireNonNull(value, "value"));
        return value.longValueExact();
    }

    /**
     * Explicit exact BigInt -> binary64 conversion. Values that round, even
     * finitely, are rejected instead of silently losing integer bits.
     */
    public static double toDoubleExact(BigInteger value) {
        validate(Objects.requireNonNull(value, "value"));
        double converted = value.doubleValue();
        if (!Double.isFinite(converted)) {
            throw new ArithmeticException("BigInt does not fit finite binary64");
        }
        BigInteger roundTrip;
        try {
            roundTrip = new BigDecimal(converted).toBigIntegerExact();
        } catch (ArithmeticException error) {
            throw new ArithmeticException("BigInt cannot be represented exactly as binary64");
        }
        if (!roundTrip.equals(value)) {
            throw new ArithmeticException("BigInt cannot be represented exactly as binary64");
        }
        return converted;
    }

    /**
     * Explicit nearest-even BigInt -> binary64 conversion. This API admits
     * precision loss by name but still rejects overflow to infinity.
     */
    public static double toDoubleNearest(BigInteger value) {
        validate(Objects.requireNonNull(value, "value"));
        double converted = value.doubleValue();
        if (!Double.isFinite(converted)) {
            throw new ArithmeticException("BigInt does not fit finite binary64");
        }
        return converted;
    }

    /** Exact arbitrary-precision negation with no binary-float coercion. */
    public static Number neg(Number value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof Float || value instanceof Double) {
            throw new IllegalArgumentException(
                    "Math.neg exact domain does not accept binary floating point");
        }
        if (value instanceof BigDecimal decimal) {
            validate(decimal);
            BigDecimal result = decimal.negate();
            validate(result);
            return result;
        }
        BigInteger integer = toBigInteger(value);
        validate(integer);
        BigInteger result = integer.negate();
        validate(result);
        return result;
    }

    /** Exact arbitrary-precision absolute value with no binary-float coercion. */
    public static Number abs(Number value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof Float || value instanceof Double) {
            throw new IllegalArgumentException(
                    "Math.abs exact domain does not accept binary floating point");
        }
        if (value instanceof BigDecimal decimal) {
            validate(decimal);
            BigDecimal result = decimal.abs();
            validate(result);
            return result;
        }
        BigInteger integer = toBigInteger(value);
        validate(integer);
        BigInteger result = integer.abs();
        validate(result);
        return result;
    }

    /**
     * Explicit decimal rounding. Precision zero is intentionally rejected here:
     * callers that want no rounding should keep the exact value rather than
     * selecting a context that looks like an active rounding policy.
     */
    public static BigDecimal round(BigDecimal value, MathContext context) {
        validate(Objects.requireNonNull(value, "value"));
        validateRoundingContext(Objects.requireNonNull(context, "context"));
        return checked(value.round(context));
    }

    /** Exact Decimal -> finite binary64 conversion; any rounding is rejected. */
    public static double toDoubleExact(BigDecimal value) {
        validate(Objects.requireNonNull(value, "value"));
        double converted = value.doubleValue();
        if (!Double.isFinite(converted)) {
            throw new ArithmeticException("Decimal does not fit finite binary64");
        }
        if (new BigDecimal(converted).compareTo(value) != 0) {
            throw new ArithmeticException("Decimal cannot be represented exactly as binary64");
        }
        return converted;
    }

    /**
     * Explicit nearest-even Decimal -> finite binary64 conversion. Precision
     * loss is admitted by the API name; overflow to infinity is still rejected.
     */
    public static double toDoubleNearest(BigDecimal value) {
        validate(Objects.requireNonNull(value, "value"));
        double converted = value.doubleValue();
        if (!Double.isFinite(converted)) {
            throw new ArithmeticException("Decimal does not fit finite binary64");
        }
        return converted;
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
        validateRoundingContext(context);
        preflightDecimal(numerator, denominator, '/');
        // RoundingMode.UNNECESSARY already rejects any inexact quotient
        // with ArithmeticException; no special global rounding state.
        return checked(numerator.divide(denominator, context));
    }

    /**
     * Conservative pre-allocation admission. A failed estimate is a quota
     * denial, not a statement that the mathematical result is intrinsically
     * out of range. All arithmetic is still validated after execution.
     */
    private static void preflightInteger(BigInteger a, BigInteger b, char operator) {
        if (operator == '*' && a.signum() != 0 && b.signum() != 0) {
            // An integer product needs at least digitCount(a)+digitCount(b)-1
            // decimal digits. Check before multiplying unbounded limbs.
            long minimumDigits = (long) a.abs().toString().length()
                    + b.abs().toString().length() - 1;
            if (minimumDigits > MAX_DIGITS) {
                throw new IllegalArgumentException("BigInt multiplication digit budget exceeded");
            }
        }
    }

    private static void preflightDecimal(BigDecimal a, BigDecimal b, char operator) {
        if (operator == '+' || operator == '-') {
            // BigDecimal.add/subtract aligns coefficients to the greater
            // *scale*, possibly materializing tens of thousands of digits.
            int targetScale = Math.max(a.scale(), b.scale());
            long first = (long) a.precision() + targetScale - a.scale();
            long second = (long) b.precision() + targetScale - b.scale();
            if (Math.max(first, second) > MAX_DIGITS + 1L) {
                throw new IllegalArgumentException("Decimal scale-alignment budget exceeded");
            }
        } else if (operator == '*') {
            long scale = (long) a.scale() + b.scale();
            if (Math.abs(scale) > MAX_ABSOLUTE_SCALE) {
                throw new IllegalArgumentException("Decimal product scale budget exceeded");
            }
            if (a.signum() != 0 && b.signum() != 0
                    && (long) a.precision() + b.precision() - 1 > MAX_DIGITS) {
                throw new IllegalArgumentException("Decimal product digit budget exceeded");
            }
        } else if ((operator == '/' || operator == '%') && b.signum() != 0) {
            // Bound the magnitude gap before BigDecimal constructs very large
            // quotient/remainder temporaries. The output scale is checked
            // precisely after arithmetic; this is an admission guard only.
            long aOrder = (long) a.precision() - a.scale();
            long bOrder = (long) b.precision() - b.scale();
            if (aOrder - bOrder > MAX_ABSOLUTE_SCALE + MAX_DIGITS) {
                throw new IllegalArgumentException("Decimal quotient magnitude budget exceeded");
            }
            if (bOrder - aOrder > MAX_ABSOLUTE_SCALE + MAX_DIGITS) {
                throw new IllegalArgumentException("Decimal quotient scale budget exceeded");
            }
        }
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

    private static void validateRoundingContext(MathContext context) {
        if (context.getPrecision() <= 0 || context.getPrecision() > MAX_DIGITS) {
            throw new IllegalArgumentException("decimal context precision out of budget");
        }
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
