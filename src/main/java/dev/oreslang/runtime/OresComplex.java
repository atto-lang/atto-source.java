package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * Immutable binary64 complex scalar used by the guest runtime.
 *
 * <p>Finite multiply/divide delegate to {@link Complex128Math}, which evaluates
 * exact binary64 input dyadics and rounds each output component once using
 * round-to-nearest, ties-to-even. Exceptional NaN/Infinity/signed-zero and
 * zero-divisor behavior uses the same versioned kernel. This value contains no
 * host capability or mutable state and is safe to retain across trusted actor
 * DATA boundaries after ordinary quota admission.</p>
 */
public record OresComplex(double real, double imaginary)
        implements OresMutex.SharedState {

    public static final OresComplex ZERO = new OresComplex(0.0, 0.0);
    public static final OresComplex ONE = new OresComplex(1.0, 0.0);
    public static final OresComplex I = new OresComplex(0.0, 1.0);

    public static OresComplex of(Object value) {
        if (value instanceof OresComplex complex) return complex;
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            throw new IllegalArgumentException(
                    "explicit conversion required from BigInt/Decimal to binary64 complex");
        }
        if (value instanceof Number number) {
            if (number instanceof Byte || number instanceof Short
                    || number instanceof Integer || number instanceof Long) {
                long integer = number.longValue();
                if (integer < -9_007_199_254_740_992L
                        || integer > 9_007_199_254_740_992L) {
                    throw new IllegalArgumentException(
                            "explicit conversion required from integer outside exact binary64 range to complex");
                }
            }
            return new OresComplex(number.doubleValue(), 0.0);
        }
        throw new IllegalArgumentException("value is not numeric");
    }

    public OresComplex add(OresComplex other) {
        Complex128Math.Value value =
                Complex128Math.add(real, imaginary, other.real, other.imaginary);
        return new OresComplex(value.real(), value.imaginary());
    }

    public OresComplex sub(OresComplex other) {
        Complex128Math.Value value =
                Complex128Math.subtract(real, imaginary, other.real, other.imaginary);
        return new OresComplex(value.real(), value.imaginary());
    }

    public OresComplex mul(OresComplex other) {
        Complex128Math.Value value =
                Complex128Math.multiply(real, imaginary, other.real, other.imaginary);
        return new OresComplex(value.real(), value.imaginary());
    }

    public OresComplex div(OresComplex other) {
        Complex128Math.Value value =
                Complex128Math.divide(real, imaginary, other.real, other.imaginary);
        return new OresComplex(value.real(), value.imaginary());
    }

    public OresComplex negate() {
        return new OresComplex(-real, -imaginary);
    }

    public OresComplex conjugate() {
        return new OresComplex(real, -imaginary);
    }

    public double abs() {
        return Math.hypot(real, imaginary);
    }

    /** Conservative language-level quota cost, not a JVM object-layout claim. */
    public long logicalBytes() {
        return 32L;
    }

    @Override
    public Iterable<?> sharedStateChildren() {
        return List.of();
    }

    @Override
    public String toString() {
        String imaginaryText = Double.toString(imaginary);
        return real + (imaginaryText.startsWith("-") ? "" : "+")
                + imaginaryText + "i";
    }
}
