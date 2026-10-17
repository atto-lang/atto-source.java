package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Complex128ExceptionalTest {
    @Test
    void finiteDividedByInfiniteRealProducesSignedZeros() {
        Complex128Math.Value positive = Complex128Math.divide(
                1.0, 0.0, Double.POSITIVE_INFINITY, 0.0);
        assertRawBits(0x0000000000000000L, positive.real());
        assertRawBits(0x0000000000000000L, positive.imaginary());

        Complex128Math.Value negative = Complex128Math.divide(
                1.0, 0.0, Double.NEGATIVE_INFINITY, 0.0);
        assertRawBits(0x8000000000000000L, negative.real());
        assertRawBits(0x8000000000000000L, negative.imaginary());
    }

    @Test
    void finiteDividedByInfiniteImaginaryPreservesQuadrantZeros() {
        Complex128Math.Value value = Complex128Math.divide(
                1.0, 0.0, 0.0, Double.POSITIVE_INFINITY);
        assertRawBits(0x0000000000000000L, value.real());
        assertRawBits(0x8000000000000000L, value.imaginary());
    }

    @Test
    void zeroDivisorUsesInfiniteScaleWithoutInventingFiniteComponents() {
        Complex128Math.Value real = Complex128Math.divide(
                1.0, 0.0, 0.0, 0.0);
        assertTrue(Double.isInfinite(real.real()));
        assertTrue(Double.isNaN(real.imaginary()));

        Complex128Math.Value imaginary = Complex128Math.divide(
                0.0, 1.0, 0.0, 0.0);
        assertTrue(Double.isNaN(imaginary.real()));
        assertTrue(Double.isInfinite(imaginary.imaginary()));
    }

    @Test
    void infiniteMultiplyRecoveryAvoidsSpuriousDoubleNan() {
        Complex128Math.Value value = Complex128Math.multiply(
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                0.0);
        assertTrue(Double.isInfinite(value.real()));
        assertTrue(Double.isInfinite(value.imaginary()));
    }

    @Test
    void exactFiniteDivisionPreservesSignedZeroQuadrants() {
        Complex128Math.Value rotated = Complex128Math.divide(
                0.0, 0.0, -0.0, 1.0);
        assertRawBits(0x0000000000000000L, rotated.real());
        assertRawBits(0x8000000000000000L, rotated.imaginary());

        Complex128Math.Value imaginaryNegativeZero = Complex128Math.divide(
                0.0, -0.0, 1.0, 0.0);
        assertRawBits(0x0000000000000000L, imaginaryNegativeZero.real());
        assertRawBits(0x8000000000000000L, imaginaryNegativeZero.imaginary());
    }

    @Test
    void subnormalDivisorIsScaledBeforeExceptionalClassification() {
        Complex128Math.Value value = Complex128Math.divide(
                Double.NaN, Double.MIN_VALUE, Double.MIN_VALUE, 0.0);
        assertTrue(Double.isNaN(value.real()));
        assertTrue(Double.isNaN(value.imaginary()));
    }

    @Test
    void infiniteOverInfiniteRemainsUnordered() {
        Complex128Math.Value value = Complex128Math.divide(
                Double.POSITIVE_INFINITY,
                0.0,
                Double.POSITIVE_INFINITY,
                0.0);
        assertTrue(Double.isNaN(value.real()));
        assertTrue(Double.isNaN(value.imaginary()));
    }

    private static void assertRawBits(long expected, double actual) {
        assertEquals(expected, Double.doubleToRawLongBits(actual));
    }
}
