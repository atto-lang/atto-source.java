package dev.oreslang;

import dev.oreslang.types.EffectAbiV1;
import dev.oreslang.types.EffectContracts.Effect;
import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class EffectAbiV1Test {
    @Test void allKnownMasksRoundTrip() {
        for (int bits = 0; bits < 1024; bits++) {
            Set<Effect> row = EffectAbiV1.decode(bits);
            assertEquals(bits, EffectAbiV1.encode(row));
            assertEquals(Integer.bitCount(bits), row.size());
        }
    }

    @Test void nativeOrderAndSpellingStayFrozen() {
        String[] spellings = {"state.read", "state.write", "suspend",
                "io", "alloc", "block", "ffi", "throw", "raise", "panic"};
        assertEquals(1023, EffectAbiV1.KNOWN_MASK);
        for (int i = 0; i < spellings.length; i++) {
            assertEquals(1 << i,
                    EffectAbiV1.encode(Set.of(EffectAbiV1.parseCanonical(spellings[i]))));
        }
    }

    @Test void unsupportedJavaFactsNeverDisappearFromNativeEffects() {
        for (Effect value : Effect.values()) {
            if (EffectAbiV1.canonicalNames().containsValue(value)) continue;
            var row = EnumSet.of(Effect.IO);
            row.add(value);
            assertThrows(IllegalArgumentException.class, () -> EffectAbiV1.encode(row));
        }
        assertThrows(IllegalArgumentException.class,
                () -> EffectAbiV1.decode(Integer.MIN_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> EffectAbiV1.decode(1024));
        assertThrows(IllegalArgumentException.class,
                () -> EffectAbiV1.parseCanonical("unresolved_call"));
        assertThrows(IllegalArgumentException.class,
                () -> EffectAbiV1.parseCanonical("io "));
    }
}
