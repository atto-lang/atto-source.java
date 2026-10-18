package dev.oreslang.nodes;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class SourceActorCheckpointCodecV1Test {
    private static List<SourceActorCheckpointCodecV1.Field> fields() {
        return List.of(
                new SourceActorCheckpointCodecV1.Field("count", "int"),
                new SourceActorCheckpointCodecV1.Field("ready", "bool"),
                new SourceActorCheckpointCodecV1.Field("name", "string"));
    }

    @Test
    void roundTripIsDeterministicAndSchemaSensitive() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("count", 42L);
        values.put("ready", true);
        values.put("name", "worker-π");

        byte[] first = SourceActorCheckpointCodecV1.encode(fields(), values);
        byte[] second = SourceActorCheckpointCodecV1.encode(fields(), values);
        assertArrayEquals(first, second);
        assertEquals(values, SourceActorCheckpointCodecV1.decode(fields(), first));

        String base = SourceActorCheckpointCodecV1.schemaSha256("Counter", fields());
        String differentOrder = SourceActorCheckpointCodecV1.schemaSha256(
                "Counter",
                List.of(fields().get(1), fields().get(0), fields().get(2)));
        String differentType = SourceActorCheckpointCodecV1.schemaSha256(
                "Counter",
                List.of(
                        new SourceActorCheckpointCodecV1.Field("count", "string"),
                        fields().get(1),
                        fields().get(2)));
        assertNotEquals(base, differentOrder);
        assertNotEquals(base, differentType);
    }

    @Test
    void rejectsFieldTamperTrailingBytesWrongTagAndOversize() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("count", 42L);
        values.put("ready", false);
        values.put("name", "worker");
        byte[] good = SourceActorCheckpointCodecV1.encode(fields(), values);

        byte[] badName = good.clone();
        // First field starts after magic(8)+count(4)+nameLen(2).
        badName[14] ^= 1;
        assertThrows(SecurityException.class,
                () -> SourceActorCheckpointCodecV1.decode(fields(), badName));

        byte[] badTag = good.clone();
        // "count" is 5 UTF-8 bytes; first tag follows 8+4+2+5.
        badTag[19] = 99;
        assertThrows(SecurityException.class,
                () -> SourceActorCheckpointCodecV1.decode(fields(), badTag));

        byte[] extra = java.util.Arrays.copyOf(good, good.length + 1);
        assertThrows(SecurityException.class,
                () -> SourceActorCheckpointCodecV1.decode(fields(), extra));

        LinkedHashMap<String, Object> huge = new LinkedHashMap<>(values);
        huge.put("name", "x".repeat(1_048_576));
        assertThrows(IllegalArgumentException.class,
                () -> SourceActorCheckpointCodecV1.encode(fields(), huge));
    }

    @Test
    void rejectsUnsupportedRuntimeShapeInsteadOfSerializingJvmIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new SourceActorCheckpointCodecV1.Field("ref", "ActorRef"));
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("count", new Object());
        values.put("ready", true);
        values.put("name", "x");
        assertThrows(SecurityException.class,
                () -> SourceActorCheckpointCodecV1.encode(fields(), values));

        LinkedHashMap<String, Object> hidden = new LinkedHashMap<>();
        hidden.put("count", 1L);
        hidden.put("ready", true);
        hidden.put("name", "ok");
        hidden.put("hiddenJvmHandle", new Object());
        assertThrows(SecurityException.class,
                () -> SourceActorCheckpointCodecV1.encode(fields(), hidden));

        LinkedHashMap<String, Object> floating = new LinkedHashMap<>();
        floating.put("count", 42.5d);
        floating.put("ready", true);
        floating.put("name", "no-coercion");
        assertThrows(SecurityException.class,
                () -> SourceActorCheckpointCodecV1.encode(fields(), floating));

        LinkedHashMap<String, Object> tooWide = new LinkedHashMap<>();
        tooWide.put("count", java.math.BigInteger.valueOf(Long.MAX_VALUE).add(java.math.BigInteger.ONE));
        tooWide.put("ready", true);
        tooWide.put("name", "overflow");
        assertThrows(SecurityException.class,
                () -> SourceActorCheckpointCodecV1.encode(fields(), tooWide));
    }

    @Test
    void preservesSignedInt64ExtremesAndUnicodeExactly() {
        for (long value : new long[]{Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE}) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("count", value);
            values.put("ready", value >= 0);
            values.put("name", "µ-🙂-" + value);
            byte[] encoded = SourceActorCheckpointCodecV1.encode(fields(), values);
            assertEquals(values, SourceActorCheckpointCodecV1.decode(fields(), encoded));
        }
    }
}
