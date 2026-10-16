package dev.oreslang;

import dev.oreslang.math.DecimalMatrix;
import dev.oreslang.math.ExactScalarMath;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixed-seed, reproducible numerical oracle checks. Never use a random seed
 * chosen from wall-clock time: the source values must reproduce in CI.
 * These exercise Java host kernels, not yet the Oreslang guest Matrix API.
 */
final class NumericPrecisionPropertyTest {
    private static final long SEED = 20261015L;

    @Test
    void twelveThousandExactScalarTriplesKeepEveryDigit() {
        Random random = new Random(SEED);
        for (int trial = 0; trial < 12_000; trial++) {
            // Values near the binary64 exact-integer boundary catch accidental
            // doubleValue() coercions while remaining far below limb quotas.
            BigInteger a = BigInteger.ONE.shiftLeft(54)
                    .add(BigInteger.valueOf(random.nextInt(200_001) - 100_000L));
            BigInteger b = BigInteger.valueOf(random.nextInt(200_001) - 100_000L);
            assertEquals(a.add(b), ExactScalarMath.evaluate(a, b, '+'),
                    "BigInt add trial " + trial);
            assertEquals(a.subtract(b), ExactScalarMath.evaluate(a, b, '-'),
                    "BigInt subtract trial " + trial);
            assertEquals(a.multiply(b), ExactScalarMath.evaluate(a, b, '*'),
                    "BigInt multiply trial " + trial);
            if (b.signum() != 0) {
                assertEquals(a.remainder(b), ExactScalarMath.evaluate(a, b, '%'),
                        "BigInt remainder trial " + trial);
            }

            // The decimal inputs have exactly two fractional digits. Binary64
            // would lose fractional products and differences in enough cases.
            BigDecimal x = BigDecimal.valueOf(random.nextInt(2_000_001) - 1_000_000L, 2);
            BigDecimal y = BigDecimal.valueOf(random.nextInt(2_000_001) - 1_000_000L, 2);
            assertEquals(0, x.add(y).compareTo(
                    (BigDecimal) ExactScalarMath.evaluate(x, y, '+')),
                    "Decimal add trial " + trial);
            assertEquals(0, x.subtract(y).compareTo(
                    (BigDecimal) ExactScalarMath.evaluate(x, y, '-')),
                    "Decimal subtract trial " + trial);
            assertEquals(0, x.multiply(y).compareTo(
                    (BigDecimal) ExactScalarMath.evaluate(x, y, '*')),
                    "Decimal multiply trial " + trial);
        }
    }

    @Test
    void fiveHundredTwelveMatrixProductsMatchIndependentScaledIntegerOracle() {
        Random random = new Random(SEED ^ 0x5A5A5A5AL);
        for (int trial = 0; trial < 512; trial++) {
            int rows = 1 + random.nextInt(4);
            int inner = 1 + random.nextInt(4);
            int cols = 1 + random.nextInt(4);
            long[] a = new long[rows * inner];
            long[] b = new long[inner * cols];
            String[] aDecimal = new String[a.length];
            String[] bDecimal = new String[b.length];
            for (int i = 0; i < a.length; i++) {
                a[i] = random.nextInt(20_001) - 10_000L;
                aDecimal[i] = BigDecimal.valueOf(a[i], 2).toPlainString();
            }
            for (int i = 0; i < b.length; i++) {
                b[i] = random.nextInt(20_001) - 10_000L;
                bDecimal[i] = BigDecimal.valueOf(b[i], 2).toPlainString();
            }
            DecimalMatrix result = DecimalMatrix.multiply(
                    DecimalMatrix.fromStrings(rows, inner, aDecimal),
                    DecimalMatrix.fromStrings(inner, cols, bDecimal),
                    MathContext.UNLIMITED);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < cols; column++) {
                    BigInteger exactUnscaled = BigInteger.ZERO;
                    for (int k = 0; k < inner; k++) {
                        exactUnscaled = exactUnscaled.add(
                                BigInteger.valueOf(a[row * inner + k]).multiply(
                                        BigInteger.valueOf(b[k * cols + column])));
                    }
                    BigDecimal expected = new BigDecimal(exactUnscaled, 4);
                    assertEquals(0, expected.compareTo(result.get(row, column)),
                            "matrix trial " + trial + " at " + row + "," + column);
                }
            }
        }
    }
}
