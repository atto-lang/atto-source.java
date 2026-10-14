package dev.oreslang;

import dev.oreslang.runtime.NumericPatternEquality;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NumericPatternEqualityTest {
    @Test
    void mixedIntegerFloatingEqualityDoesNotRoundAboveTwoToThe53() {
        assertEquals(Boolean.TRUE, NumericPatternEquality.equal(1L, 1.0));
        assertEquals(Boolean.TRUE, NumericPatternEquality.equal(9007199254740992L, 9007199254740992.0));
        assertEquals(Boolean.FALSE, NumericPatternEquality.equal(9007199254740993L, 9007199254740992.0));
        assertEquals(Boolean.FALSE, NumericPatternEquality.equal(9007199254740992.0, 9007199254740993L));
        assertEquals(Boolean.FALSE, NumericPatternEquality.equal(Long.MAX_VALUE, 0x1.0p63));
        assertEquals(Boolean.TRUE, NumericPatternEquality.equal(Long.MIN_VALUE, -0x1.0p63));
    }

    @Test
    void floatsPreserveIeeeNanAndSignedZeroSemantics() {
        Double nan = Double.NaN;
        assertEquals(Boolean.FALSE, NumericPatternEquality.equal(nan, nan));
        assertEquals(Boolean.FALSE, NumericPatternEquality.equal(nan, 0.0));
        assertEquals(Boolean.FALSE, NumericPatternEquality.equal(Double.POSITIVE_INFINITY, Long.MAX_VALUE));
        assertEquals(Boolean.TRUE, NumericPatternEquality.equal(-0.0, 0.0));
        assertEquals(Boolean.TRUE, NumericPatternEquality.equal(0L, -0.0));
        assertEquals(Boolean.TRUE, NumericPatternEquality.equal(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
    }

    @Test
    void arbitraryPrecisionPayloadsNeverCoerceThroughDouble() {
        assertEquals(Boolean.TRUE, NumericPatternEquality.equal(
                new BigInteger("9007199254740993"), 9007199254740993L));
        assertEquals(Boolean.FALSE, NumericPatternEquality.equal(
                new BigInteger("9007199254740993"), 9007199254740992.0));
        assertEquals(Boolean.TRUE, NumericPatternEquality.equal(
                new BigDecimal("1.000"), 1L));
        assertEquals(Boolean.FALSE, NumericPatternEquality.equal(
                new BigDecimal("0.1"), 0.1));
    }

    @Test
    void unknownNumericCarriersNeverEnterStaticProof() {
        Number custom = new Number() {
            @Override public int intValue() { return 1; }
            @Override public long longValue() { return 1; }
            @Override public float floatValue() { return 1; }
            @Override public double doubleValue() { return 1; }
        };
        assertNull(NumericPatternEquality.equal(custom, 1L));
    }
}
