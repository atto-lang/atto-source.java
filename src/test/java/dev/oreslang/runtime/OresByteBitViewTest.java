package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the actual immutable Bytes owner, not a synthetic JS model.
 * Host runtime proof only; AttoLang guest AOT/loan checker remains separate.
 */
final class OresByteBitViewTest {
    @Test
    void byteAndBitViewsHaveExactBoundaries() {
        try (OresBytes owner = OresBytes.copyOf(new byte[] {(byte) 0xa5, 42})) {
            OresByteBitView entire = OresByteBitView.of(owner);
            assertEquals(16, entire.bitLength());
            assertEquals(0xa5, entire.byteAt(0));
            assertEquals(42, entire.byteAt(1));
            assertEquals(1, entire.bitAt(0));
            assertEquals(0, entire.bitAt(1));
            assertEquals(1, entire.bitAt(2));
            assertEquals(0, entire.bitAt(3));
            assertEquals(0, entire.bitAt(4));
            assertEquals(1, entire.bitAt(5));
            assertEquals(0, entire.bitAt(6));
            assertEquals(1, entire.bitAt(7));
            assertTrue(entire.byteAligned());

            OresByteBitView unaligned = entire.skipBits(3);
            assertEquals(3, unaligned.startBit());
            assertEquals(13, unaligned.bitLength());
            assertFalse(unaligned.byteAligned());
            assertEquals(0, unaligned.bitAt(0));
            assertEquals(0, unaligned.bitAt(1));
            assertEquals(1, unaligned.bitAt(2));
            assertThrows(IllegalStateException.class, () -> unaligned.byteAt(0));
            OresByteBitView second = unaligned.skipBits(5);
            assertEquals(8, second.startBit());
            assertEquals(42, second.byteAt(0));
            assertEquals(0, second.skipBits(8).bitLength());
            assertEquals(42, entire.bitSlice(8,16).byteAt(0));
        }
    }

    @Test
    void exactZeroLengthAndBoundsFailClosed() {
        try (OresBytes owner = OresBytes.copyOf(new byte[] {1})) {
            OresByteBitView bits = OresByteBitView.of(owner);
            assertThrows(IndexOutOfBoundsException.class, () -> bits.skipBits(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> bits.skipBits(9));
            assertThrows(IndexOutOfBoundsException.class, () -> bits.bitSlice(-1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> bits.bitSlice(3, 9));
            assertThrows(IndexOutOfBoundsException.class, () -> bits.bitSlice(5, 4));
            assertThrows(IndexOutOfBoundsException.class, () -> bits.bitAt(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> bits.bitAt(8));
            assertThrows(IndexOutOfBoundsException.class, () -> bits.byteAt(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> bits.byteAt(1));
            OresByteBitView zero = bits.skipBits(8);
            assertTrue(zero.byteAligned());
            assertEquals(0, zero.byteLength());
            assertThrows(IndexOutOfBoundsException.class, () -> zero.bitAt(0));
        }
        try (OresBytes empty = OresBytes.empty()) {
            assertEquals(0, OresByteBitView.of(empty).bitLength());
        }
    }

    @Test
    void originalOwnerTakeInvalidatesEveryBorrowedView() {
        OresBytes original = OresBytes.copyOf(new byte[] {1, 2});
        OresByteBitView parent = OresByteBitView.of(original);
        OresByteBitView nested = parent.skipBits(3);
        OresBytes receivingOwner = original.take();
        try {
            assertThrows(IllegalStateException.class, () -> parent.byteAt(0));
            assertThrows(IllegalStateException.class, () -> nested.bitAt(0));
            assertThrows(IllegalStateException.class, nested::bitLength);
            assertThrows(IllegalStateException.class, () -> parent.skipBits(1));
            assertEquals(1, receivingOwner.byteAt(0));
        } finally {
            receivingOwner.close();
        }
    }

    @Test
    void ownerCloseInvalidatesEvenEmptyBorrowedView() {
        OresBytes original = OresBytes.empty();
        OresByteBitView empty = OresByteBitView.of(original);
        original.close();
        assertThrows(IllegalStateException.class, empty::bitLength);
        assertThrows(IllegalStateException.class, empty::byteAligned);
        assertThrows(IllegalStateException.class, () -> empty.skipBits(0));
    }

    @Test
    void viewCannotAcquireMutableRawBufferOrOwnAnotherHeap() {
        assertEquals(1, OresByteBitView.class.getDeclaredFields().length - 2,
                "view owns exactly one Bytes reference plus two scalar offsets");
        assertTrue(java.lang.reflect.Modifier.isFinal(OresByteBitView.class.getModifiers()));
        assertFalse(AutoCloseable.class.isAssignableFrom(OresByteBitView.class));
    }
}
