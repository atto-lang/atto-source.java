package dev.oreslang;

import dev.oreslang.math.ExactScalarMath;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exact host numerical carriers must never silently pass through binary64. */
final class ExactScalarMathTest {
    private static void sameDecimal(String expected, Number actual) {
        assertInstanceOf(BigDecimal.class, actual);
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual));
    }

    @Test
    void exactBigIntArithmeticFarBeyondI64() {
        BigInteger huge = new BigInteger("9".repeat(300));
        assertEquals(huge.add(BigInteger.ONE), ExactScalarMath.evaluate(huge, 1L, '+'));
        assertEquals(huge.subtract(BigInteger.ONE), ExactScalarMath.evaluate(huge, 1L, '-'));
        assertEquals(huge.multiply(huge), ExactScalarMath.evaluate(huge, huge, '*'));
        assertEquals(BigInteger.ZERO, ExactScalarMath.evaluate(huge, 9L, '%'));
        assertEquals(huge.negate(), ExactScalarMath.evaluate(BigInteger.ZERO, huge, '-'));
    }

    @Test
    void exactDecimalArithmeticKeepsDecimalFractions() {
        sameDecimal("0.3", ExactScalarMath.evaluate(
                new BigDecimal("0.1"), new BigDecimal("0.2"), '+'));
        sameDecimal("0.02", ExactScalarMath.evaluate(
                new BigDecimal("0.1"), new BigDecimal("0.2"), '*'));
        sameDecimal("0.25", ExactScalarMath.evaluate(
                new BigDecimal("1"), new BigDecimal("4"), '/'));
        sameDecimal("1e100", ExactScalarMath.evaluate(
                new BigDecimal("1e100"), BigInteger.ZERO, '+'));
    }

    @Test
    void exactUnaryAbsAndNegPreserveExactDomainsAndDecimalScale() {
        BigInteger huge = BigInteger.ONE.shiftLeft(1024);
        assertEquals(huge.negate(), ExactScalarMath.neg(huge));
        assertEquals(huge, ExactScalarMath.abs(huge.negate()));
        assertEquals(BigInteger.valueOf(-42), ExactScalarMath.neg(42L));
        assertEquals(BigInteger.valueOf(42), ExactScalarMath.abs(-42L));

        BigDecimal scaled = new BigDecimal("-12.3400");
        Number absolute = ExactScalarMath.abs(scaled);
        assertInstanceOf(BigDecimal.class, absolute);
        assertEquals("12.3400", ((BigDecimal) absolute).toPlainString());
        assertEquals(4, ((BigDecimal) absolute).scale());

        BigDecimal positive = new BigDecimal("12.3400");
        Number negated = ExactScalarMath.neg(positive);
        assertInstanceOf(BigDecimal.class, negated);
        assertEquals("-12.3400", ((BigDecimal) negated).toPlainString());
        assertEquals(4, ((BigDecimal) negated).scale());

        assertThrows(IllegalArgumentException.class, () -> ExactScalarMath.abs(-0.0d));
        assertThrows(IllegalArgumentException.class, () -> ExactScalarMath.neg(0.5f));
    }

    @Test
    void exactUnaryOperationsEnforceInputBudgetsBeforeWork() {
        BigInteger tooLarge = new BigInteger("9".repeat(4097));
        assertThrows(IllegalArgumentException.class, () -> ExactScalarMath.abs(tooLarge));
        assertThrows(IllegalArgumentException.class, () -> ExactScalarMath.neg(tooLarge));
        BigDecimal excessiveScale = new BigDecimal("1e-20000");
        assertThrows(IllegalArgumentException.class, () -> ExactScalarMath.abs(excessiveScale));
        assertThrows(IllegalArgumentException.class, () -> ExactScalarMath.neg(excessiveScale));
    }

    @Test
    void namedBigIntDivisionUsesTruncationTowardZeroAndDividendSignedRemainder() {
        for (long[] values : new long[][]{
                {7, 3, 2, 1},
                {-7, 3, -2, -1},
                {7, -3, -2, 1},
                {-7, -3, 2, -1}
        }) {
            BigInteger a = BigInteger.valueOf(values[0]);
            BigInteger b = BigInteger.valueOf(values[1]);
            ExactScalarMath.IntegerDivision result = ExactScalarMath.divRem(a, b);
            assertEquals(BigInteger.valueOf(values[2]), result.quotient());
            assertEquals(BigInteger.valueOf(values[3]), result.remainder());
            assertEquals(a, result.quotient().multiply(b).add(result.remainder()));
            assertTrue(result.remainder().abs().compareTo(b.abs()) < 0);
            assertEquals(result.quotient(), ExactScalarMath.quotient(a, b));
            assertEquals(result.remainder(), ExactScalarMath.remainder(a, b));
        }

        BigInteger huge = BigInteger.ONE.shiftLeft(256).add(BigInteger.valueOf(12345));
        BigInteger divisor = BigInteger.ONE.shiftLeft(128);
        ExactScalarMath.IntegerDivision large = ExactScalarMath.divRem(huge, divisor);
        assertEquals(BigInteger.ONE.shiftLeft(128), large.quotient());
        assertEquals(BigInteger.valueOf(12345), large.remainder());
        assertThrows(ArithmeticException.class,
                () -> ExactScalarMath.divRem(BigInteger.ONE, BigInteger.ZERO));
    }

    @Test
    void bigIntNarrowingAndBinary64ConversionAreExplicit() {
        assertEquals(Long.MAX_VALUE,
                ExactScalarMath.toLongExact(BigInteger.valueOf(Long.MAX_VALUE)));
        assertEquals(Long.MIN_VALUE,
                ExactScalarMath.toLongExact(BigInteger.valueOf(Long.MIN_VALUE)));
        assertThrows(ArithmeticException.class, () ->
                ExactScalarMath.toLongExact(BigInteger.ONE.shiftLeft(63)));
        assertThrows(ArithmeticException.class, () ->
                ExactScalarMath.toLongExact(BigInteger.ONE.shiftLeft(63).negate()
                        .subtract(BigInteger.ONE)));

        BigInteger exactly = BigInteger.ONE.shiftLeft(53);
        assertEquals(0x4340000000000000L,
                Double.doubleToRawLongBits(ExactScalarMath.toDoubleExact(exactly)));
        BigInteger inexact = exactly.add(BigInteger.ONE);
        assertThrows(ArithmeticException.class, () ->
                ExactScalarMath.toDoubleExact(inexact));
        assertEquals(0x4340000000000000L,
                Double.doubleToRawLongBits(ExactScalarMath.toDoubleNearest(inexact)));

        BigInteger tooLargeForFiniteBinary64 = BigInteger.ONE.shiftLeft(2000);
        assertThrows(ArithmeticException.class, () ->
                ExactScalarMath.toDoubleExact(tooLargeForFiniteBinary64));
        assertThrows(ArithmeticException.class, () ->
                ExactScalarMath.toDoubleNearest(tooLargeForFiniteBinary64));
    }

    @Test
    void nonterminatingDivideRequiresExplicitRoundingContext() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExactScalarMath.evaluate(new BigDecimal("1"), new BigDecimal("3"), '/'));
        assertTrue(error.getMessage().contains("MathContext"));
        sameDecimal("0.3333333333", ExactScalarMath.divide(
                BigDecimal.ONE, new BigDecimal("3"), new MathContext(10, RoundingMode.HALF_EVEN)));
        assertThrows(ArithmeticException.class, () -> ExactScalarMath.divide(
                BigDecimal.ONE, new BigDecimal("3"), new MathContext(10, RoundingMode.UNNECESSARY)));
        assertThrows(IllegalArgumentException.class, () -> ExactScalarMath.divide(
                BigDecimal.ONE, new BigDecimal("3"),
                new MathContext(1_000_000, RoundingMode.HALF_EVEN)));
        assertThrows(IllegalArgumentException.class, () -> ExactScalarMath.divide(
                BigDecimal.ONE, new BigDecimal("3"), MathContext.UNLIMITED));
    }

    @Test
    void noImplicitFloatingPointConversionOrUnspecifiedBigIntDivision() {
        BigInteger integer = BigInteger.TEN.pow(100);
        assertThrows(IllegalArgumentException.class, () ->
                ExactScalarMath.evaluate(integer, 1.0d, '+'));
        assertThrows(IllegalArgumentException.class, () ->
                ExactScalarMath.evaluate(new BigDecimal("0.1"), 0.2d, '+'));
        assertThrows(IllegalArgumentException.class, () ->
                ExactScalarMath.evaluate(integer, 3L, '/'));
    }

    @Test
    void preserveModuloAndZeroDivisionErrors() {
        assertEquals(new BigInteger("-1"), ExactScalarMath.evaluate(
                new BigInteger("-7"), BigInteger.valueOf(3), '%'));
        sameDecimal("0.1", ExactScalarMath.evaluate(
                new BigDecimal("1.3"), new BigDecimal("0.4"), '%'));
        assertThrows(ArithmeticException.class, () ->
                ExactScalarMath.evaluate(new BigDecimal("1"), BigDecimal.ZERO, '/'));
        assertThrows(ArithmeticException.class, () ->
                ExactScalarMath.evaluate(BigInteger.ONE, BigInteger.ZERO, '%'));
    }

    @Test
    void preflightRejectsLargeProductsAndDecimalAlignmentBeforeAllocation() {
        BigInteger large = new BigInteger("9".repeat(3000));
        BigInteger other = new BigInteger("9".repeat(2000));
        IllegalArgumentException bigint = assertThrows(IllegalArgumentException.class,
                () -> ExactScalarMath.evaluate(large, other, '*'));
        assertTrue(bigint.getMessage().contains("multiplication digit budget"));

        IllegalArgumentException decimal = assertThrows(IllegalArgumentException.class,
                () -> ExactScalarMath.evaluate(new BigDecimal("9".repeat(3000)),
                        new BigDecimal("9".repeat(2000)), '*'));
        assertTrue(decimal.getMessage().contains("product digit budget"));

        IllegalArgumentException alignment = assertThrows(IllegalArgumentException.class,
                () -> ExactScalarMath.evaluate(BigDecimal.ONE,
                        new BigDecimal("1e-10000"), '+'));
        assertTrue(alignment.getMessage().contains("scale-alignment budget"));

        assertThrows(IllegalArgumentException.class, () ->
                ExactScalarMath.evaluate(new BigDecimal("1e16000"),
                        new BigDecimal("1e-16000"), '/'));

        // Small exact inputs with different scales remain accepted.
        sameDecimal("1.0001", ExactScalarMath.evaluate(
                BigDecimal.ONE, new BigDecimal("0.0001"), '+'));
    }

    @Test
    void enforceScalarBudgetsBeforeImplicitGrowth() {
        assertThrows(IllegalArgumentException.class, () ->
                ExactScalarMath.evaluate(new BigInteger("9".repeat(4096)), 1L, '+'));
        assertThrows(IllegalArgumentException.class, () ->
                ExactScalarMath.evaluate(new BigDecimal("1e-10000"),
                        new BigDecimal("1e-10000"), '*'));
        assertThrows(IllegalArgumentException.class, () ->
                ExactScalarMath.evaluate(new BigDecimal("1e-20000"), BigDecimal.ZERO, '+'));
    }
}
