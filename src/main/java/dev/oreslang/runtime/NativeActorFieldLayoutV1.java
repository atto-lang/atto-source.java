package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Logical actor-field layout ABI shared by JVM/Graal and the LLVM backend.
 * The fingerprint is an interoperability check, NOT an actor capability.
 * No FFM segment or native pointer escapes through these descriptors.
 *
 * See contracts/v1/ACTOR_FIELD_LAYOUT_V1.md for canonical byte encoding.
 */
public final class NativeActorFieldLayoutV1 {
    public static final int VERSION = 1;
    public static final int MAX_FIELDS = 4096;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private NativeActorFieldLayoutV1() {}

    public enum FieldType { I64 }

    public record Field(String name, FieldType type, boolean mutable) {
        public Field {
            checkIdentifier(name, "field");
            Objects.requireNonNull(type, "field type");
            if (type != FieldType.I64) {
                throw new IllegalArgumentException("unsupported native actor field type");
            }
        }
    }

    public static long fingerprint(String actorName, List<Field> fields) {
        checkIdentifier(actorName, "actor");
        Objects.requireNonNull(fields, "fields");
        if (fields.isEmpty() || fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("actor field count must be 1..4096");
        }
        Set<String> seen = new HashSet<>();
        long hash = FNV_OFFSET;
        hash = appendTerminatedAscii(hash, "ores.actor.fields.v1");
        hash = appendTerminatedAscii(hash, actorName);
        for (Field field : fields) {
            Objects.requireNonNull(field, "actor field");
            if (!seen.add(field.name())) {
                throw new IllegalArgumentException("duplicate actor field: " + field.name());
            }
            hash = appendTerminatedAscii(hash, field.name());
            hash = appendTerminatedAscii(hash, "i64");
            hash = appendByte(hash, field.mutable() ? 1 : 0);
        }
        return hash;
    }

    public static void requireMatching(
            int nativeVersion, long nativeFingerprint,
            String actorName, List<Field> fields) {
        if (nativeVersion != VERSION
                || nativeFingerprint != fingerprint(actorName, fields)) {
            throw new IllegalArgumentException("LLVM/Graal native actor field layout mismatch");
        }
    }

    private static long appendByte(long accumulator, int unsignedByte) {
        return (accumulator ^ (unsignedByte & 0xffL)) * FNV_PRIME;
    }

    private static long appendTerminatedAscii(long hash, String value) {
        for (byte octet : value.getBytes(StandardCharsets.US_ASCII)) {
            hash = appendByte(hash, octet);
        }
        return appendByte(hash, 0);
    }

    private static void checkIdentifier(String identifier, String role) {
        Objects.requireNonNull(identifier, role + " identifier");
        if (!identifier.matches("[A-Za-z_][A-Za-z_0-9]*")) {
            throw new IllegalArgumentException("invalid " + role + " field layout identifier");
        }
    }
}
