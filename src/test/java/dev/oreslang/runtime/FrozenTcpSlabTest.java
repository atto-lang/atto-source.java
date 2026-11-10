package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class FrozenTcpSlabTest {
    @Test
    void portableByteVectorsAndImmutableInputSnapshot() {
        byte[] input = {0, 1, 127, (byte) 128, (byte) 255};
        var slab = new FrozenTcpSlab(input);
        Arrays.fill(input, (byte) 0);
        var p = slab.pin();
        assertEquals(5, slab.size());
        assertEquals(128, slab.readByte(p, 3));
        assertEquals(255, slab.readByte(p, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> slab.readByte(p, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> slab.readByte(p, 5));
        slab.retire();
        assertThrows(IllegalStateException.class, slab::pin);
        assertFalse(slab.freed());
        assertEquals(128, slab.readByte(p, 3), "existing pin remains live");
        slab.releasePin(p);
        assertTrue(slab.freed());
        assertThrows(IllegalStateException.class, () -> slab.readByte(p, 3));
        assertThrows(IllegalStateException.class, () -> slab.releasePin(p));
    }

    @Test
    void wrongSlabAndMissingPinCannotReadBytes() {
        var a = new FrozenTcpSlab(new byte[] {42});
        var b = new FrozenTcpSlab(new byte[] {42});
        var p = a.pin();
        assertThrows(IllegalStateException.class, () -> b.readByte(p, 0));
        assertThrows(IllegalStateException.class, () -> a.readByte(null, 0));
        assertThrows(IllegalStateException.class, () -> b.releasePin(p));
        assertEquals(42, a.readByte(p, 0));
        a.releasePin(p);
    }

    @Test
    void independentPinsKeepSlabLiveUntilLastRelease() {
        var slab = new FrozenTcpSlab(new byte[] {1});
        var first = slab.pin();
        var second = slab.pin();
        slab.retire();
        slab.releasePin(first);
        assertFalse(slab.freed());
        assertEquals(1, slab.readByte(second, 0));
        slab.releasePin(second);
        assertTrue(slab.freed());
    }

    @Test
    void byteBudgetAndEmptySlabFailClosed() {
        assertThrows(NullPointerException.class, () -> new FrozenTcpSlab(null));
        assertThrows(IllegalArgumentException.class,
                () -> new FrozenTcpSlab(new byte[FrozenTcpSlab.MAX_BYTES + 1]));
        var empty = new FrozenTcpSlab(new byte[0]);
        var p = empty.pin();
        assertThrows(IndexOutOfBoundsException.class, () -> empty.readByte(p, 0));
        empty.releasePin(p);
        empty.retire();
        assertTrue(empty.freed());
    }
}
