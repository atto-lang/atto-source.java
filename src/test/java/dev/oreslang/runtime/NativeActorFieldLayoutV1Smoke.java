package dev.oreslang.runtime;

import java.util.List;

/** No-dependency executable mirror proof for Java 21+ funded runners. */
public final class NativeActorFieldLayoutV1Smoke {
    private NativeActorFieldLayoutV1Smoke() {}

    public static void main(String[] args) {
        var fields = List.of(
            new NativeActorFieldLayoutV1.Field(
                "count", NativeActorFieldLayoutV1.FieldType.I64, true),
            new NativeActorFieldLayoutV1.Field(
                "offset", NativeActorFieldLayoutV1.FieldType.I64, true));
        var expected = Long.parseUnsignedLong("b2d57409773a17ed", 16);
        NativeActorFieldLayoutV1.requireMatching(1, expected, "Counter", fields);
        var readonly = List.of(new NativeActorFieldLayoutV1.Field(
            "offset", NativeActorFieldLayoutV1.FieldType.I64, false));
        var other = Long.parseUnsignedLong("abaa8c384f278d29", 16);
        NativeActorFieldLayoutV1.requireMatching(1, other, "Readonly", readonly);
        if (NativeActorFieldLayoutV1.fingerprint(
                "Counter", List.of(fields.get(1), fields.get(0))) == expected)
            throw new AssertionError("swapped field indexes falsely match");
        try {
            NativeActorFieldLayoutV1.requireMatching(2, expected, "Counter", fields);
            throw new AssertionError("unsupported layout version accepted");
        } catch (IllegalArgumentException expectedFailure) {
            // Correctly fail closed.
        }
        try {
            NativeActorFieldLayoutV1.requireMatching(1, expected ^ 1, "Counter", fields);
            throw new AssertionError("layout hash mismatch accepted");
        } catch (IllegalArgumentException expectedFailure) {
            // Correctly fail closed.
        }
        System.out.println("JAVA_LLVM_ACTOR_LAYOUT_V1_OK");
    }
}
