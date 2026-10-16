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
