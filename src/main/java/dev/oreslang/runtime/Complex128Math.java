package dev.oreslang.runtime;

import java.math.BigInteger;

/**
 * Correctly rounded finite binary64 complex scalar arithmetic.
 *
 * <p>Finite multiplication and division are evaluated from the exact dyadic
 * values encoded by their IEEE-754 inputs, then rounded once per output
 * component using round-to-nearest, ties-to-even. The integer widths are
 * bounded by the binary64 exponent/significand domain; there is no
 * precision-context heuristic. Exceptional NaN/Infinity/signed-zero and
 * zero-divisor recovery follows the versioned complex128 IEEE-special contract
 * and compiler-rt-style recovery rules; finite results still use the exact
 * dyadic path above rather than the exceptional fallback.
 */
public final class Complex128Math {
    private static final BigInteger TWO_POW_52 = BigInteger.ONE.shiftLeft(52);
    private static final BigInteger TWO_POW_53 = BigInteger.ONE.shiftLeft(53);

    private Complex128Math() {}

    public record Value(double real, double imaginary) {}

    public static Value add(double ar, double ai, double br, double bi) {
        return new Value(ar + br, ai + bi);
    }

    public static Value subtract(double ar, double ai, double br, double bi) {
        return new Value(ar - br, ai - bi);
    }

    public static Value conjugate(double real, double imaginary) {
        return new Value(real, -imaginary);
    }

    public static Value multiply(double ar, double ai, double br, double bi) {
        double ac = ar * br;
        double bd = ai * bi;
        double ad = ar * bi;
        double bc = ai * br;
        double realResult = ac - bd;
        double imaginaryResult = ad + bc;

        if (!allFinite(ar, ai, br, bi)) {
            return recoverExceptionalMultiply(
                    ar, ai, br, bi,
                    ac, bd, ad, bc,
                    realResult, imaginaryResult);
        }

        Dyadic a = fromFiniteDouble(ar);
        Dyadic b = fromFiniteDouble(ai);
        Dyadic c = fromFiniteDouble(br);
        Dyadic d = fromFiniteDouble(bi);
        Dyadic exactReal = add(multiply(a, c), negate(multiply(b, d)));
        Dyadic exactImaginary = add(multiply(a, d), multiply(b, c));
        return new Value(
                roundDyadic(exactReal, negativeZeroHint(realResult)),
                roundDyadic(exactImaginary, negativeZeroHint(imaginaryResult)));
    }

    public static Value divide(double ar, double ai, double br, double bi) {
        if (allFinite(ar, ai, br, bi) && (br != 0.0 || bi != 0.0)) {
            return divideExactFinite(ar, ai, br, bi);
        }
        return divideExceptional(ar, ai, br, bi);
    }

    private static Value recoverExceptionalMultiply(
            double ar, double ai, double br, double bi,
            double ac, double bd, double ad, double bc,
            double realResult, double imaginaryResult) {
        if (!(Double.isNaN(realResult) && Double.isNaN(imaginaryResult))) {
            return new Value(realResult, imaginaryResult);
        }

        boolean recalculate = false;
        if (Double.isInfinite(ar) || Double.isInfinite(ai)) {
            ar = Math.copySign(Double.isInfinite(ar) ? 1.0 : 0.0, ar);
            ai = Math.copySign(Double.isInfinite(ai) ? 1.0 : 0.0, ai);
            if (Double.isNaN(br)) br = Math.copySign(0.0, br);
            if (Double.isNaN(bi)) bi = Math.copySign(0.0, bi);
            recalculate = true;
        }
        if (Double.isInfinite(br) || Double.isInfinite(bi)) {
            br = Math.copySign(Double.isInfinite(br) ? 1.0 : 0.0, br);
            bi = Math.copySign(Double.isInfinite(bi) ? 1.0 : 0.0, bi);
            if (Double.isNaN(ar)) ar = Math.copySign(0.0, ar);
            if (Double.isNaN(ai)) ai = Math.copySign(0.0, ai);
            recalculate = true;
        }
        if (!recalculate && (Double.isInfinite(ac) || Double.isInfinite(bd)
                || Double.isInfinite(ad) || Double.isInfinite(bc))) {
            if (Double.isNaN(ar)) ar = Math.copySign(0.0, ar);
            if (Double.isNaN(ai)) ai = Math.copySign(0.0, ai);
            if (Double.isNaN(br)) br = Math.copySign(0.0, br);
            if (Double.isNaN(bi)) bi = Math.copySign(0.0, bi);
            recalculate = true;
        }

        if (recalculate) {
            realResult = Double.POSITIVE_INFINITY * (ar * br - ai * bi);
            imaginaryResult = Double.POSITIVE_INFINITY * (ar * bi + ai * br);
        }
        return new Value(realResult, imaginaryResult);
    }

    private static Value divideExceptional(
            double ar, double ai, double br, double bi) {
        // Match compiler-rt's divisor scaling before exceptional recovery.
        // Without this, a finite subnormal divisor can square to zero and be
        // mistaken for an actual zero divisor when NaNs are also present.
        double divisorScale = Math.max(Math.abs(br), Math.abs(bi));
        int divisorExponent = 0;
        if (divisorScale > 0.0 && Double.isFinite(divisorScale)) {
            divisorExponent = Math.getExponent(divisorScale);
            br = Math.scalb(br, -divisorExponent);
            bi = Math.scalb(bi, -divisorExponent);
        }

        double denominator = br * br + bi * bi;
        double realResult = Math.scalb(
                (ar * br + ai * bi) / denominator, -divisorExponent);
        double imaginaryResult = Math.scalb(
                (ai * br - ar * bi) / denominator, -divisorExponent);

        if (Double.isNaN(realResult) && Double.isNaN(imaginaryResult)) {
            if (denominator == 0.0
                    && (!Double.isNaN(ar) || !Double.isNaN(ai))) {
                double infinity = Math.copySign(Double.POSITIVE_INFINITY, br);
                realResult = infinity * ar;
                imaginaryResult = infinity * ai;
            } else if ((Double.isInfinite(ar) || Double.isInfinite(ai))
                    && Double.isFinite(br) && Double.isFinite(bi)) {
                double a = Math.copySign(Double.isInfinite(ar) ? 1.0 : 0.0, ar);
                double b = Math.copySign(Double.isInfinite(ai) ? 1.0 : 0.0, ai);
                realResult = Double.POSITIVE_INFINITY * (a * br + b * bi);
                imaginaryResult = Double.POSITIVE_INFINITY * (b * br - a * bi);
            } else if ((Double.isInfinite(br) || Double.isInfinite(bi))
                    && Double.isFinite(ar) && Double.isFinite(ai)) {
                double c = Math.copySign(Double.isInfinite(br) ? 1.0 : 0.0, br);
                double d = Math.copySign(Double.isInfinite(bi) ? 1.0 : 0.0, bi);
                realResult = 0.0 * (ar * c + ai * d);
                imaginaryResult = 0.0 * (ai * c - ar * d);
            }
        }
        return new Value(realResult, imaginaryResult);
    }

    static Value divideExactFinite(double ar, double ai, double br, double bi) {
        if (!allFinite(ar, ai, br, bi) || (br == 0.0 && bi == 0.0)) {
            throw new IllegalArgumentException(
                    "exact finite complex division requires finite operands and nonzero divisor");
        }

        Dyadic a = fromFiniteDouble(ar);
        Dyadic b = fromFiniteDouble(ai);
        Dyadic c = fromFiniteDouble(br);
        Dyadic d = fromFiniteDouble(bi);
        Dyadic denominator = add(multiply(c, c), multiply(d, d));
        Dyadic realNumerator = add(multiply(a, c), multiply(b, d));
        Dyadic imaginaryNumerator = add(multiply(b, c), negate(multiply(a, d)));

        // Exact dyadic arithmetic intentionally erases the sign of zero.
        // Preserve IEEE zero quadrants from the corresponding primitive
        // numerator expressions; the exact denominator is strictly positive.
        double realZeroHint = ar * br + ai * bi;
        double imaginaryZeroHint = ai * br - ar * bi;
        return new Value(
                roundRatio(realNumerator, denominator,
                        negativeZeroHint(realZeroHint)),
                roundRatio(imaginaryNumerator, denominator,
                        negativeZeroHint(imaginaryZeroHint)));
    }

    private static boolean allFinite(double a, double b, double c, double d) {
        return Double.isFinite(a) && Double.isFinite(b)
                && Double.isFinite(c) && Double.isFinite(d);
    }

    private record Dyadic(BigInteger significand, int exponent2) {
        private Dyadic {
            if (significand.signum() == 0) exponent2 = 0;
        }
    }

    private static Dyadic fromFiniteDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        long bits = Double.doubleToRawLongBits(value);
        boolean negative = (bits & Long.MIN_VALUE) != 0;
        int exponentBits = (int) ((bits >>> 52) & 0x7ffL);
        long fraction = bits & 0x000f_ffff_ffff_ffffL;
        if (exponentBits == 0 && fraction == 0L) {
            return new Dyadic(BigInteger.ZERO, 0);
        }

        BigInteger significand;
        int exponent2;
        if (exponentBits == 0) {
            significand = BigInteger.valueOf(fraction);
            exponent2 = -1074;
        } else {
            significand = BigInteger.valueOf(fraction | (1L << 52));
            exponent2 = exponentBits - 1023 - 52;
        }
        if (negative) significand = significand.negate();
        return normalize(new Dyadic(significand, exponent2));
    }

    private static Dyadic multiply(Dyadic left, Dyadic right) {
        if (left.significand.signum() == 0 || right.significand.signum() == 0) {
            return new Dyadic(BigInteger.ZERO, 0);
        }
        return normalize(new Dyadic(
                left.significand.multiply(right.significand),
                Math.addExact(left.exponent2, right.exponent2)));
    }

    private static Dyadic add(Dyadic left, Dyadic right) {
        if (left.significand.signum() == 0) return right;
        if (right.significand.signum() == 0) return left;

        int commonExponent = Math.min(left.exponent2, right.exponent2);
        BigInteger leftSig =
                left.significand.shiftLeft(left.exponent2 - commonExponent);
        BigInteger rightSig =
                right.significand.shiftLeft(right.exponent2 - commonExponent);
        return normalize(new Dyadic(leftSig.add(rightSig), commonExponent));
    }

    private static Dyadic negate(Dyadic value) {
        return new Dyadic(value.significand.negate(), value.exponent2);
    }

    private static Dyadic normalize(Dyadic value) {
        BigInteger sig = value.significand;
        if (sig.signum() == 0) return new Dyadic(BigInteger.ZERO, 0);
        int trailing = sig.abs().getLowestSetBit();
        if (trailing <= 0) return value;
        return new Dyadic(
                sig.shiftRight(trailing),
                Math.addExact(value.exponent2, trailing));
    }

    private static double roundDyadic(Dyadic value, boolean negativeZero) {
        if (value.significand.signum() == 0) {
            return negativeZero ? -0.0 : 0.0;
        }
        return roundPositiveRational(
                value.significand.abs(),
                BigInteger.ONE,
                value.exponent2,
                value.significand.signum() < 0);
    }

    private static double roundRatio(
            Dyadic numerator, Dyadic denominator, boolean negativeZero) {
        if (denominator.significand.signum() <= 0) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        if (numerator.significand.signum() == 0) {
            return negativeZero ? -0.0 : 0.0;
        }
        return roundPositiveRational(
                numerator.significand.abs(),
                denominator.significand,
                Math.subtractExact(
                        numerator.exponent2, denominator.exponent2),
                numerator.significand.signum() < 0);
    }

    private static double roundPositiveRational(
            BigInteger numerator,
            BigInteger denominator,
            int exponent2,
            boolean negative) {
        if (numerator.signum() <= 0 || denominator.signum() <= 0) {
            throw new IllegalArgumentException("ratio must be positive");
        }

        int binaryExponent =
                Math.addExact(floorLog2Ratio(numerator, denominator), exponent2);
        long signBit = negative ? Long.MIN_VALUE : 0L;

        if (binaryExponent < -1022) {
            BigInteger q = roundQuotient(
                    numerator,
                    denominator,
                    Math.addExact(exponent2, 1074));
            if (q.signum() == 0) {
                return Double.longBitsToDouble(signBit);
            }
            if (q.compareTo(TWO_POW_52) >= 0) {
                return Double.longBitsToDouble(signBit | (1L << 52));
            }
            return Double.longBitsToDouble(signBit | q.longValueExact());
        }

        int shift = Math.subtractExact(exponent2, binaryExponent - 52);
        BigInteger q = roundQuotient(numerator, denominator, shift);
        if (q.compareTo(TWO_POW_53) >= 0) {
            q = q.shiftRight(1);
            binaryExponent++;
        }
        if (binaryExponent > 1023) {
            return negative
                    ? Double.NEGATIVE_INFINITY
                    : Double.POSITIVE_INFINITY;
        }

        long exponentField = ((long) binaryExponent + 1023L) << 52;
        long fraction = q.subtract(TWO_POW_52).longValueExact();
        return Double.longBitsToDouble(signBit | exponentField | fraction);
    }

    private static int floorLog2Ratio(
            BigInteger numerator, BigInteger denominator) {
        int candidate = numerator.bitLength() - denominator.bitLength();
        boolean atLeast;
        if (candidate >= 0) {
            atLeast =
                    numerator.compareTo(denominator.shiftLeft(candidate)) >= 0;
        } else {
            atLeast =
                    numerator.shiftLeft(-candidate).compareTo(denominator) >= 0;
        }
        return atLeast ? candidate : candidate - 1;
    }

    private static BigInteger roundQuotient(
            BigInteger numerator, BigInteger denominator, int binaryShift) {
        BigInteger scaledNumerator = numerator;
        BigInteger scaledDenominator = denominator;
        if (binaryShift >= 0) {
            scaledNumerator = numerator.shiftLeft(binaryShift);
        } else {
            scaledDenominator = denominator.shiftLeft(-binaryShift);
        }

        BigInteger[] qr =
                scaledNumerator.divideAndRemainder(scaledDenominator);
        int halfCompare = qr[1].shiftLeft(1).compareTo(scaledDenominator);
        if (halfCompare > 0
                || (halfCompare == 0 && qr[0].testBit(0))) {
            return qr[0].add(BigInteger.ONE);
        }
        return qr[0];
    }

    private static boolean negativeZeroHint(double value) {
        return value == 0.0 && Double.doubleToRawLongBits(value) < 0;
    }
}
