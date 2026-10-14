package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.NominalMessageValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NominalMessageTransportTest {
    @Test
    void sharedAndPrivateActorDispatchCarriesImmutableTaggedPayloads() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("delta", 21L);
        NominalMessageValue sent = new NominalMessageValue(
                "source-a.ores", "module.Messages", "Messages", "Delta", 2L, source);
        source.put("delta", 99L);
        assertEquals(21L, sent.payload().get("delta"));
        assertThrows(UnsupportedOperationException.class,
                () -> sent.payload().put("delta", 40L));
        try (ActorRuntime runtime = new ActorRuntime()) {
            for (ActorRuntime.ActorKind kind : new ActorRuntime.ActorKind[] {
                    ActorRuntime.ActorKind.SHARED, ActorRuntime.ActorKind.PRIVATE }) {
                AtomicReference<NominalMessageValue> delivered = new AtomicReference<>();
                Object returned = runtime.invoke(kind, sent, (message, context) -> {
                    delivered.set(message);
                    return message;
                });
                assertEquals(sent, delivered.get());
                assertEquals(sent, returned);
                if (kind == ActorRuntime.ActorKind.PRIVATE) {
                    assertNotSame(sent, delivered.get());
                }
            }
        }
    }

    @Test
    void forgedMutablePayloadAndWrongTagsFailClosed() {
        Map<String, Object> unsafe = new LinkedHashMap<>();
        unsafe.put("delta", new StringBuilder("mutable"));
        assertThrows(IllegalArgumentException.class, () -> new NominalMessageValue(
                "a.ores", "m.Messages", "Messages", "Delta", 2L, unsafe));
        assertThrows(IllegalArgumentException.class, () -> new NominalMessageValue(
                "a.ores", "m.Messages", "Messages", "Delta", 0L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new NominalMessageValue(
                "a.ores", "m.Messages", "Wrong", "Delta", 2L, Map.of()));
        Map<String, Object> nullPayload = new LinkedHashMap<>();
        nullPayload.put("delta", null);
        assertThrows(NullPointerException.class, () -> new NominalMessageValue(
                "a.ores", "m.Messages", "Messages", "Delta", 2L, nullPayload));
    }

    @Test
    void sameEnumNamesAndTagsAcrossSourceUnitsHaveDifferentIdentity() {
        NominalMessageValue a = new NominalMessageValue(
                "a.ores", "m.Messages", "Messages", "Delta", 2L, Map.of("delta", 1L));
        NominalMessageValue b = new NominalMessageValue(
                "b.ores", "m.Messages", "Messages", "Delta", 2L, Map.of("delta", 1L));
        assertNotEquals(a, b);
        assertSame(a, ActorRuntime.freeze(a));
    }
}
