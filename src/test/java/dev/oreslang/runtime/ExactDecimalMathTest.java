package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

final class ExactDecimalMathTest {
    @Test void supportsExplicitFiftyAndOneHundredDigitPrecision() {
        BigDecimal a = ExactDecimalMath.divide(BigDecimal.ONE, new BigDecimal("7"), 50, "HALF_EVEN");
        assertEquals("0.14285714285714285714285714285714285714285714285714", a.toPlainString());
        assertEquals(50, a.precision());
        BigDecimal b = ExactDecimalMath.sqrt(new BigDecimal("2"), 100, "HALF_EVEN");
        assertEquals(100, b.precision());
        assertTrue(b.toPlainString().startsWith("1.414213562373095048801688724209698078569"));
    }

    @Test void roundingModeIsExplicitAndHalfEvenHandlesTies() {
        assertEquals("2", ExactDecimalMath.round(new BigDecimal("2.5"), 1, "HALF_EVEN").toPlainString());
        assertEquals("4", ExactDecimalMath.round(new BigDecimal("3.5"), 1, "HALF_EVEN").toPlainString());
        assertEquals("3", ExactDecimalMath.round(new BigDecimal("2.5"), 1, "HALF_UP").toPlainString());
        assertThrows(ArithmeticException.class,
                () -> ExactDecimalMath.divide(BigDecimal.ONE, new BigDecimal("3"), 50, "UNNECESSARY"));
    }

    @Test void rejectsInvalidContextsAndDomains() {
        assertThrows(IllegalArgumentException.class, () -> ExactDecimalMath.divide(BigDecimal.ONE, BigDecimal.ONE, 0, "HALF_EVEN"));
        assertThrows(IllegalArgumentException.class, () -> ExactDecimalMath.divide(BigDecimal.ONE, BigDecimal.ONE, 4097, "HALF_EVEN"));
        assertThrows(IllegalArgumentException.class, () -> ExactDecimalMath.round(BigDecimal.ONE, 10, "half_even"));
        assertThrows(ArithmeticException.class, () -> ExactDecimalMath.divide(BigDecimal.ONE, BigDecimal.ZERO, 50, "HALF_EVEN"));
        assertThrows(ArithmeticException.class, () -> ExactDecimalMath.sqrt(new BigDecimal("-1"), 50, "HALF_EVEN"));
    }

    @Test void rejectsOversizedOperandsAndSupportsBoundaryContext() {
        BigDecimal extreme = BigDecimal.ONE.scaleByPowerOfTen(250_000);
        assertThrows(ArithmeticException.class,
                () -> ExactDecimalMath.sqrt(extreme, 50, "HALF_EVEN"));
        assertEquals("1", ExactDecimalMath.divide(BigDecimal.ONE, BigDecimal.ONE, 4096, "HALF_EVEN").toPlainString());
    }
}
