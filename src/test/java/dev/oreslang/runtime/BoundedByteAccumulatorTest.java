package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BoundedByteAccumulatorTest {
    @Test void randomizedChunkPartitionsPreserveEveryByte() {
        var random = new Random(141);
        for (int i = 0; i < 100; i++) {
            byte[] expected = new byte[i * 137];
            random.nextBytes(expected);
            try (var buffer = new BoundedByteAccumulator(expected.length)) {
                int at = 0;
                while (at < expected.length) {
                    int end = Math.min(expected.length, at + 1 + random.nextInt(193));
                    buffer.append(Arrays.copyOfRange(expected, at, end));
                    at = end;
                }
                assertArrayEquals(expected, buffer.detach());
            }
        }
    }

    @Test void budgetFailureIsAtomicAndDetachIsOneShot() {
        try (var buffer = new BoundedByteAccumulator(4)) {
            byte[] source = {1, 2};
            buffer.append(source);
            source[0] = 9;
            assertThrows(IllegalArgumentException.class, () -> buffer.append(new byte[3]));
            buffer.append(new byte[] {3, 4});
            assertArrayEquals(new byte[] {1, 2, 3, 4}, buffer.detach());
            assertThrows(IllegalStateException.class, buffer::detach);
            assertThrows(IllegalStateException.class, () -> buffer.append(new byte[0]));
        }
        try (var zero = new BoundedByteAccumulator(0)) {
            zero.append(new byte[0]);
            assertArrayEquals(new byte[0], zero.detach());
        }
        var closed = new BoundedByteAccumulator(4);
        closed.append(new byte[] {7});
        closed.close();
        closed.close();
        assertThrows(IllegalStateException.class, closed::detach);
        assertThrows(IllegalArgumentException.class, () -> new BoundedByteAccumulator(-1));
    }

    @Test void joinPreservesOwnershipAndRepeatedParts() {
        try (var first = OresBytes.copyOf(new byte[] {0, (byte) 255});
             var second = OresBytes.copyOf(new byte[] {(byte) 128});
             var empty = OresBytes.empty();
             var joined = OresBytes.join(List.of(first, empty, second, first))) {
            assertArrayEquals(new byte[] {0, (byte) 255, (byte) 128, 0, (byte) 255}, joined.toArrayCopy());
            assertEquals(255, first.byteAt(1));
            first.close();
            assertEquals(255, joined.byteAt(1));
        }
    }

    @Test void joinRejectsOversizeAndInvalidOrRetiredPartsWithoutConsumingInputs() {
        try (var full = OresBytes.copyOf(new byte[OresBytes.MAX_LENGTH]);
             var one = OresBytes.copyOf(new byte[] {1});
             var retired = OresBytes.empty()) {
            retired.close();
            try (var exact = OresBytes.join(List.of(full))) {
                assertEquals(OresBytes.MAX_LENGTH, exact.size());
            }
            assertThrows(IllegalArgumentException.class, () -> OresBytes.join(List.of(full, one)));
            assertThrows(IllegalArgumentException.class, () -> OresBytes.join(List.of(one, "bad")));
            assertThrows(IllegalStateException.class, () -> OresBytes.join(List.of(one, retired)));
            assertEquals(1, one.byteAt(0));
        }
    }
}
