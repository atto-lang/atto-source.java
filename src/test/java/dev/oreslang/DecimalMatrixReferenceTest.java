package dev.oreslang;

import dev.oreslang.math.DecimalMatrix;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-Java oracle for the future guest Matrix<Decimal> API. */
final class DecimalMatrixReferenceTest {
    private static void equalDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but got " + actual);
    }

    @Test
    void exactFractionalRectangularProduct() {
        DecimalMatrix left = DecimalMatrix.fromStrings(2, 2,
                "0.1", "0.2", "1e30", "-1e30");
        DecimalMatrix right = DecimalMatrix.fromStrings(2, 2,
                "0.3", "2", "0.4", "2");
        DecimalMatrix product = DecimalMatrix.multiply(left, right, MathContext.UNLIMITED);
        equalDecimal("0.11", product.get(0, 0));
        equalDecimal("0.6", product.get(0, 1));
        equalDecimal("-1e29", product.get(1, 0));
        equalDecimal("0", product.get(1, 1));
    }

    @Test
    void cancellationKeepsUnitTermWithFinitePrecisionContext() {
        DecimalMatrix a = DecimalMatrix.fromStrings(1, 3, "1e80", "1", "-1e80");
        DecimalMatrix b = DecimalMatrix.fromStrings(3, 1, "1", "1", "1");
        DecimalMatrix c = DecimalMatrix.multiply(a, b, new MathContext(20, RoundingMode.HALF_EVEN));
        equalDecimal("1", c.get(0, 0));
    }

    @Test
    void explicitOutputContextRoundsExactlyOnce() {
        DecimalMatrix a = DecimalMatrix.fromStrings(1, 1, "1.23456");
        DecimalMatrix b = DecimalMatrix.fromStrings(1, 1, "1");
        DecimalMatrix out = DecimalMatrix.zeros(1, 1);
        DecimalMatrix.multiplyInto(out, a, b, new MathContext(3, RoundingMode.HALF_EVEN));
        equalDecimal("1.23", out.get(0, 0));
        equalDecimal("1.23456", a.get(0, 0));
    }

    @Test
    void dimensionlessProductsPreserveShapes() {
        DecimalMatrix a = DecimalMatrix.zeros(0, 3);
        DecimalMatrix b = DecimalMatrix.fromStrings(3, 2, "1", "2", "3", "4", "5", "6");
        DecimalMatrix zeroRows = DecimalMatrix.multiply(a, b, MathContext.UNLIMITED);
        assertEquals(0, zeroRows.rows());
        assertEquals(2, zeroRows.columns());

        DecimalMatrix zeroInner = DecimalMatrix.multiply(
                DecimalMatrix.zeros(2, 0), DecimalMatrix.zeros(0, 3),
                MathContext.UNLIMITED);
        assertEquals(2, zeroInner.rows());
        assertEquals(3, zeroInner.columns());
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 3; j++) equalDecimal("0", zeroInner.get(i, j));
        }
    }

    @Test
    void arithmeticFailureDoesNotPublishPartialOutput() {
        DecimalMatrix input = DecimalMatrix.fromStrings(2, 1, "1", "1e-10000");
        DecimalMatrix factor = DecimalMatrix.fromStrings(1, 1, "1e-10000");
        DecimalMatrix output = DecimalMatrix.fromStrings(2, 1, "7", "8");
        assertThrows(IllegalArgumentException.class,
                () -> DecimalMatrix.multiplyInto(output, input, factor, MathContext.UNLIMITED));
        equalDecimal("7", output.get(0, 0));
        equalDecimal("8", output.get(1, 0));
    }

    @Test
    void matrixWorkAndDecimalMagnitudeBudgetsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () ->
                DecimalMatrix.multiply(DecimalMatrix.zeros(100, 100),
                        DecimalMatrix.zeros(100, 20), MathContext.UNLIMITED));
        assertThrows(IllegalArgumentException.class, () ->
                DecimalMatrix.fromStrings(1, 1, "9".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () ->
                DecimalMatrix.fromStrings(1, 1, "1e1000000"));
        assertThrows(IllegalArgumentException.class, () -> DecimalMatrix.zeros(65537, 0));
    }

    @Test
    void rejectsAggregateInputDigitBudget() {
        String[] values = new String[256];
        java.util.Arrays.fill(values, "9".repeat(4096));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DecimalMatrix.fromStrings(256, 1, values));
        assertTrue(error.getMessage().contains("significant-digit budget"));
    }

    @Test
    void aggregateOutputFailureKeepsEveryDestinationCellUnmodified() {
        String[] values = new String[256];
        java.util.Arrays.fill(values, "9".repeat(2048));
        DecimalMatrix left = DecimalMatrix.fromStrings(256, 1, values);
        DecimalMatrix right = DecimalMatrix.fromStrings(1, 2,
                "9".repeat(2048), "9".repeat(2048));
        DecimalMatrix output = DecimalMatrix.zeros(256, 2);
        assertThrows(IllegalArgumentException.class, () ->
                DecimalMatrix.multiplyInto(output, left, right, MathContext.UNLIMITED));
        equalDecimal("0", output.get(0, 0));
        equalDecimal("0", output.get(255, 1));
    }

    @Test
    void invalidInnerDimensionDetectedBeforeOutputAllocation() {
        DecimalMatrix left = DecimalMatrix.zeros(1000, 0);
        DecimalMatrix right = DecimalMatrix.zeros(1, 1000);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DecimalMatrix.multiply(left, right, MathContext.UNLIMITED));
        assertTrue(error.getMessage().contains("shape mismatch"));
    }

    @Test
    void oversizedRoundingContextRejectedBeforeAllocatingOrChangingDestination() {
        DecimalMatrix left = DecimalMatrix.fromStrings(1, 1, "0.25");
        DecimalMatrix right = DecimalMatrix.fromStrings(1, 1, "4");
        DecimalMatrix output = DecimalMatrix.fromStrings(1, 1, "17");
        MathContext unboundedRequest = new MathContext(1_000_000, RoundingMode.HALF_EVEN);
        assertThrows(IllegalArgumentException.class, () ->
                DecimalMatrix.multiply(left, right, unboundedRequest));
        assertThrows(IllegalArgumentException.class, () ->
                DecimalMatrix.multiplyInto(output, left, right, unboundedRequest));
        equalDecimal("17", output.get(0, 0));
        // Precision zero remains legal for exact decimal multiplication.
        equalDecimal("1", DecimalMatrix.multiply(
                left, right, MathContext.UNLIMITED).get(0, 0));
    }

    @Test
    void rejectMismatchesAliasesAndUnboundedAllocations() {
        DecimalMatrix left = DecimalMatrix.fromStrings(1, 2, "1", "2");
        DecimalMatrix wrong = DecimalMatrix.fromStrings(1, 2, "3", "4");
        assertThrows(IllegalArgumentException.class, () ->
                DecimalMatrix.multiply(left, wrong, MathContext.UNLIMITED));
        DecimalMatrix identity = DecimalMatrix.fromStrings(2, 2, "1", "0", "0", "1");
        assertThrows(IllegalArgumentException.class, () ->
                DecimalMatrix.multiplyInto(identity, identity, identity, MathContext.UNLIMITED));
        assertThrows(IllegalArgumentException.class, () -> DecimalMatrix.zeros(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> DecimalMatrix.zeros(1001, 1000));
        assertThrows(IllegalArgumentException.class, () ->
                DecimalMatrix.fromStrings(2, 2, "1", "2", "3"));
    }
}
