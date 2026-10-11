package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

final class NativeActorFieldLayoutV1Test {
    private static final List<NativeActorFieldLayoutV1.Field> COUNTER = List.of(
        new NativeActorFieldLayoutV1.Field("count", NativeActorFieldLayoutV1.FieldType.I64, true),
        new NativeActorFieldLayoutV1.Field("offset", NativeActorFieldLayoutV1.FieldType.I64, true)
    );

    @Test
    void matchesCompiledLlvmVersionAndHash() {
        long expected = Long.parseUnsignedLong("b2d57409773a17ed", 16);
        assertEquals(expected, NativeActorFieldLayoutV1.fingerprint("Counter", COUNTER));
        assertDoesNotThrow(() -> NativeActorFieldLayoutV1.requireMatching(
            1, expected, "Counter", COUNTER));
        assertThrows(IllegalArgumentException.class, () ->
            NativeActorFieldLayoutV1.requireMatching(2, expected, "Counter", COUNTER));
        assertThrows(IllegalArgumentException.class, () ->
            NativeActorFieldLayoutV1.requireMatching(1, expected ^ 1L, "Counter", COUNTER));
    }

    @Test
    void readonlyAndFieldOrderArePartOfLayoutIdentity() {
        long readonly = Long.parseUnsignedLong("abaa8c384f278d29", 16);
        assertEquals(readonly, NativeActorFieldLayoutV1.fingerprint(
            "Readonly", List.of(new NativeActorFieldLayoutV1.Field(
                "offset", NativeActorFieldLayoutV1.FieldType.I64, false))));
        assertNotEquals(NativeActorFieldLayoutV1.fingerprint("Counter", COUNTER),
            NativeActorFieldLayoutV1.fingerprint("Counter", List.of(COUNTER.get(1), COUNTER.get(0))));
        assertNotEquals(NativeActorFieldLayoutV1.fingerprint("Counter", COUNTER),
            NativeActorFieldLayoutV1.fingerprint("Counter", List.of(
                COUNTER.get(0), new NativeActorFieldLayoutV1.Field(
                    "offset", NativeActorFieldLayoutV1.FieldType.I64, false))));
    }

    @Test
    void invalidDescriptorsFailBeforeHeapAdmission() {
        var f = COUNTER.get(0);
        assertThrows(IllegalArgumentException.class, () ->
            NativeActorFieldLayoutV1.fingerprint("Counter", List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            NativeActorFieldLayoutV1.fingerprint("Counter", List.of(f, f)));
        assertThrows(IllegalArgumentException.class, () ->
            NativeActorFieldLayoutV1.fingerprint("Counter-NotAscii", COUNTER));
        assertThrows(IllegalArgumentException.class, () ->
            new NativeActorFieldLayoutV1.Field("not allowed", NativeActorFieldLayoutV1.FieldType.I64, true));
        assertThrows(NullPointerException.class, () ->
            NativeActorFieldLayoutV1.fingerprint("Counter", List.of(f, null)));
        ArrayList<NativeActorFieldLayoutV1.Field> oversized = new ArrayList<>();
        for (int n = 0; n < 4097; n++) oversized.add(f);
        assertThrows(IllegalArgumentException.class, () ->
            NativeActorFieldLayoutV1.fingerprint("Counter", oversized));
    }
}
