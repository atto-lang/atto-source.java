package dev.oreslang;

import dev.oreslang.types.NumericValueEquality;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Host reference checks; real guest and native AOT receipts remain required. */
final class NumericValueEqualityExactTest {
    @Test void decimalTextAndBinaryRationalAreNotTheSameValue() {
        assertFalse(NumericValueEquality.equals(new BigDecimal("0.1"), 0.1d));
        assertFalse(NumericValueEquality.equals(0.1d, new BigDecimal("0.1")));
        assertFalse(NumericValueEquality.equals(new BigDecimal("0.1"), 0.1f));
        assertTrue(NumericValueEquality.equals(new BigDecimal("0.5"), 0.5d));
        assertTrue(NumericValueEquality.equals(0.5d, new BigDecimal("0.5")));
    }

    @Test void signedIntegerAndRoundedFloatAreNotConflated() {
        assertFalse(NumericValueEquality.equals(9007199254740993L, 9007199254740992d));
        assertFalse(NumericValueEquality.equals(9007199254740992d, 9007199254740993L));
        assertTrue(NumericValueEquality.equals(9007199254740992L, 9007199254740992d));
        assertFalse(NumericValueEquality.equals(Long.MAX_VALUE, 9223372036854775808d));
        assertTrue(NumericValueEquality.equals(BigInteger.valueOf(Long.MAX_VALUE),
                Long.MAX_VALUE));
    }

    @Test void ieeeNanUnorderedAndSignedZeroEqual() {
        assertFalse(NumericValueEquality.equals(Double.NaN, Double.NaN));
        assertFalse(NumericValueEquality.equals(Float.NaN, Float.NaN));
        assertFalse(NumericValueEquality.equals(Double.NaN, 0L));
        assertFalse(NumericValueEquality.equals(0L, Float.NaN));
        assertTrue(NumericValueEquality.equals(+0d, -0d));
        assertTrue(NumericValueEquality.equals(-0d, 0L));
        assertTrue(NumericValueEquality.equals(Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY));
        assertFalse(NumericValueEquality.equals(Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY));
    }

    @Test void unknownHostNumberCannotBecomeGuestNumber() {
        assertThrows(IllegalArgumentException.class,
                () -> NumericValueEquality.equals(new AtomicLong(1), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> NumericValueEquality.equals(1L, new AtomicLong(1)));
    }
}
