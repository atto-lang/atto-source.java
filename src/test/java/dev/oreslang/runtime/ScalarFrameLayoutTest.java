package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure frame-shape tests. They are not actor execution, JVM stack-safety or
 * guest parser/typechecker evidence until the checked layout is wired in.
 */
final class ScalarFrameLayoutTest {
    @Test
    void emptyFrameContainsOnlyHeader() {
        ScalarFrameLayout shape = ScalarFrameLayout.checked(0, 0);
        assertEquals(0, shape.locals());
        assertEquals(0, shape.operands());
        assertEquals(16, shape.bytes());
        assertThrows(IndexOutOfBoundsException.class, () -> shape.localOffset(0));
        assertThrows(IndexOutOfBoundsException.class, () -> shape.operandOffset(0));
    }

    @Test
    void mixedMaximumShapeHasNonOverlappingSlotsAndExactBounds() {
        ScalarFrameLayout shape = ScalarFrameLayout.checked(128, 128);
        assertEquals(4112, shape.bytes());
        assertEquals(16, shape.localOffset(0));
        assertEquals(2048, shape.localOffset(127));
        assertEquals(2064, shape.operandOffset(0));
        assertEquals(4096, shape.operandOffset(127));
        assertEquals(shape.bytes(), shape.operandOffset(127) + ScalarFrameLayout.SLOT_BYTES);
    }

    @Test
    void exactSlotBudgetAllowedForEitherRegion() {
        assertEquals(ScalarFrameLayout.MAX_BYTES, ScalarFrameLayout.checked(256, 0).bytes());
        assertEquals(ScalarFrameLayout.MAX_BYTES, ScalarFrameLayout.checked(0, 256).bytes());
    }

    @Test
    void rejectsNegativeCountsAndOversizedCombinedSlots() {
        assertThrows(IllegalArgumentException.class, () -> ScalarFrameLayout.checked(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> ScalarFrameLayout.checked(0, -1));
        assertThrows(IllegalArgumentException.class, () -> ScalarFrameLayout.checked(257, 0));
        assertThrows(IllegalArgumentException.class, () -> ScalarFrameLayout.checked(129, 128));
        assertThrows(IllegalArgumentException.class, () -> ScalarFrameLayout.checked(256, 1));
    }

    @Test
    void rejectsIntegerOverflowInputsBeforeCalculatingByteSize() {
        assertThrows(IllegalArgumentException.class,
                () -> ScalarFrameLayout.checked(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarFrameLayout.checked(Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarFrameLayout.checked(1, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarFrameLayout.checked(Integer.MIN_VALUE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarFrameLayout.checked(0, Integer.MIN_VALUE));
    }

    @Test
    void offsetAccessCannotEscapeOwnRegion() {
        ScalarFrameLayout shape = ScalarFrameLayout.checked(2, 3);
        assertEquals(16, shape.localOffset(0));
        assertEquals(32, shape.localOffset(1));
        assertEquals(48, shape.operandOffset(0));
        assertEquals(80, shape.operandOffset(2));
        assertThrows(IndexOutOfBoundsException.class, () -> shape.localOffset(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> shape.localOffset(2));
        assertThrows(IndexOutOfBoundsException.class, () -> shape.operandOffset(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> shape.operandOffset(3));
        assertThrows(IndexOutOfBoundsException.class, () -> shape.operandOffset(Integer.MAX_VALUE));
    }
}
