package dev.oreslang;

import dev.oreslang.runtime.CheckedBitCursor;
import dev.oreslang.runtime.CheckedBitCursor.Endian;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests a Java runtime bit decoder, NOT guest-language binary pattern lowering.
 * This class is deliberately safe for a closed-world Native Image link.
 */
final class CheckedBitCursorTest {
    @Test
    void unalignedBigEndianFieldsAndSignedExtraction() {
        CheckedBitCursor cursor = new CheckedBitCursor(
                new byte[]{(byte) 0xb1, (byte) 0xff});
        assertEquals(5L, cursor.readUnsigned(3, Endian.BIG));
        assertEquals(17L, cursor.readUnsigned(5, Endian.BIG));
        assertEquals(-1L, cursor.readSigned(8, Endian.BIG));
        assertEquals(16L, cursor.positionBits());
        assertEquals(0L, cursor.remainingBits());
    }

    @Test
    void bigAndLittleEndianByteOrderNeverConfuseBitOrder() {
        assertEquals(0x1234L, new CheckedBitCursor(new byte[]{0x12, 0x34})
                .readUnsigned(16, Endian.BIG));
        assertEquals(0x3412L, new CheckedBitCursor(new byte[]{0x12, 0x34})
                .readUnsigned(16, Endian.LITTLE));
        assertEquals(-32767L, new CheckedBitCursor(new byte[]{1, (byte) 0x80})
                .readSigned(16, Endian.LITTLE));
    }

    @Test
    void everyBitWidthFromOneToSixtyFourSignExtendsCorrectly() {
        byte[] allOnes = new byte[8];
        Arrays.fill(allOnes, (byte) 0xff);
        for (int width = 1; width <= 64; width++) {
            long expected = width == 64 ? -1L : (1L << width) - 1L;
            assertEquals(expected, new CheckedBitCursor(allOnes)
                    .readUnsigned(width, Endian.BIG), "unsigned " + width);
            assertEquals(-1L, new CheckedBitCursor(allOnes)
                    .readSigned(width, Endian.BIG), "signed " + width);
        }
    }

    @Test
    void invalidWidthsShortReadsAndOverflowsNeverAdvance() {
        CheckedBitCursor cursor = new CheckedBitCursor(new byte[]{42});
        for (int width : new int[]{-1, 0, 9, 65, Integer.MAX_VALUE}) {
            assertThrows(RuntimeException.class,
                    () -> cursor.readUnsigned(width, Endian.BIG));
            assertEquals(0L, cursor.positionBits());
        }
        assertThrows(IllegalArgumentException.class,
                () -> cursor.readUnsigned(3, Endian.LITTLE));
        assertThrows(IndexOutOfBoundsException.class,
                () -> cursor.skipBits(Long.MAX_VALUE));
        assertThrows(IndexOutOfBoundsException.class,
                () -> cursor.skipBits(-1L));
        assertEquals(0L, cursor.positionBits());
        cursor.skipBits(8L);
        assertEquals(8L, cursor.positionBits());
        assertThrows(IndexOutOfBoundsException.class,
                () -> cursor.readUnsigned(1, Endian.BIG));
        assertEquals(8L, cursor.positionBits());
    }

    @Test
    void callerMutationCannotChangeSnapshotBytes() {
        byte[] input = new byte[]{12, 45};
        CheckedBitCursor cursor = new CheckedBitCursor(input);
        Arrays.fill(input, (byte) 0);
        assertEquals(12L, cursor.readUnsigned(8, Endian.BIG));
        assertEquals(45L, cursor.readUnsigned(8, Endian.BIG));
        assertEquals(2, cursor.lengthBytes());
        assertThrows(NullPointerException.class,
                () -> new CheckedBitCursor(null));
    }
}
