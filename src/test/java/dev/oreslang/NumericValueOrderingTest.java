package dev.oreslang;

import dev.oreslang.types.NumericValueOrdering;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Pure Java arithmetic oracles; separate guest execution is also required. */
final class NumericValueOrderingTest {
    @Test void nanIsUnorderedOnEitherSide() {
        assertNull(NumericValueOrdering.compare(Double.NaN, 0d));
        assertNull(NumericValueOrdering.compare(0d, Double.NaN));
        assertNull(NumericValueOrdering.compare(Double.NaN, Double.NaN));
    }

    @Test void signedZeroHasEqualOrdering() {
        assertEquals(Integer.valueOf(0), NumericValueOrdering.compare(-0.0d, +0.0d));
        assertEquals(Integer.valueOf(0), NumericValueOrdering.compare(-0.0d, 0L));
        assertEquals(Integer.valueOf(0), NumericValueOrdering.compare(0L, -0.0d));
    }

    @Test void exactI64AboveBinary64IntegerPrecision() {
        assertEquals(Integer.valueOf(1),
                NumericValueOrdering.compare(9007199254740993L, 9007199254740992.0d));
        assertEquals(Integer.valueOf(-1),
                NumericValueOrdering.compare(9007199254740992.0d, 9007199254740993L));
        assertEquals(Integer.valueOf(-1),
                NumericValueOrdering.compare(Long.MAX_VALUE, 9223372036854775808.0d));
        assertEquals(Integer.valueOf(1),
                NumericValueOrdering.compare(9223372036854775808.0d, Long.MAX_VALUE));
    }

    @Test void decimalAndBinaryFloatUseExactValueNotDisplaySpelling() {
        assertEquals(Integer.valueOf(-1),
                NumericValueOrdering.compare(new BigDecimal("0.1"), 0.1d));
        assertEquals(Integer.valueOf(1),
                NumericValueOrdering.compare(0.1d, new BigDecimal("0.1")));
        assertEquals(Integer.valueOf(1),
                NumericValueOrdering.compare(new BigInteger("100000000000000000000000000000000000000"), 1.0d));
    }

    @Test void infinitiesOrderAroundFiniteValuesWithoutBigintCoercion() {
        assertEquals(Integer.valueOf(1),
                NumericValueOrdering.compare(Double.POSITIVE_INFINITY, BigInteger.TEN));
        assertEquals(Integer.valueOf(-1),
                NumericValueOrdering.compare(BigInteger.TEN, Double.POSITIVE_INFINITY));
        assertEquals(Integer.valueOf(1),
                NumericValueOrdering.compare(BigInteger.ONE.shiftLeft(4096), -Double.MAX_VALUE));
    }

    @Test void unsupportedNumberCarrierFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> NumericValueOrdering.compare(new AtomicInteger(1), 1L));
    }
}
