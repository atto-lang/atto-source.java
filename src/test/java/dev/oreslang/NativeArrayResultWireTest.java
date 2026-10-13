package dev.oreslang;

import dev.oreslang.types.NativeArrayResultWire;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NativeArrayResultWireTest {
    @Test
    void canonicalSequencesMatchNativeContract() {
        final var ok = NativeArrayResultWire.Result.ok();
        final var limit = NativeArrayResultWire.Result.err(NativeArrayResultWire.ArrayError.LIMIT);
        final var oom = NativeArrayResultWire.Result.err(NativeArrayResultWire.ArrayError.NO_MEMORY);
        assertArrayEquals(new byte[] {1, 0, 0}, NativeArrayResultWire.encode(ok));
        assertArrayEquals(new byte[] {1, 1, 1}, NativeArrayResultWire.encode(limit));
        assertArrayEquals(new byte[] {1, 1, 2}, NativeArrayResultWire.encode(oom));
        for (var value : new NativeArrayResultWire.Result[] {ok, limit, oom}) {
            assertEquals(value, NativeArrayResultWire.decode(NativeArrayResultWire.encode(value)));
        }
    }

    @Test
    void rejectsAllNoncanonicalVariantAndErrorBytePairs() {
        for (int variant = 0; variant < 256; ++variant) {
            for (int error = 0; error < 256; ++error) {
                final byte[] bytes = {1, (byte) variant, (byte) error};
                final boolean valid = (variant == 0 && error == 0) ||
                    (variant == 1 && (error == 1 || error == 2));
                if (valid) {
                    assertNotNull(NativeArrayResultWire.decode(bytes));
                } else {
                    assertThrows(IllegalArgumentException.class,
                        () -> NativeArrayResultWire.decode(bytes.clone()));
                }
            }
        }
    }

    @Test
    void rejectsUnknownVersionAndInvalidSizes() {
        assertThrows(NullPointerException.class, () -> NativeArrayResultWire.decode(null));
        assertThrows(IllegalArgumentException.class, () -> NativeArrayResultWire.decode(new byte[] {1, 0}));
        assertThrows(IllegalArgumentException.class, () -> NativeArrayResultWire.decode(new byte[] {1, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> NativeArrayResultWire.decode(new byte[] {2, 0, 0}));
        assertThrows(IllegalArgumentException.class,
            () -> new NativeArrayResultWire.Result(NativeArrayResultWire.Variant.OK,
                    NativeArrayResultWire.ArrayError.LIMIT));
        assertThrows(NullPointerException.class,
            () -> NativeArrayResultWire.Result.err(null));
    }
}
