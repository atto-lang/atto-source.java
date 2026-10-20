package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class OresEntryHostCarrierBoundaryTest {
    @Test
    void hostContainersCannotConflateDistinctNominalEntryCarriers() {
        OresEntry first = new OresEntry("key", List.of(1, 2));
        OresEntry second = new OresEntry("key", List.of(1, 2));

        assertNotSame(first, second);
        assertNotEquals(first, second);
        assertEquals(first, first);

        Set<OresEntry> hostSet = new HashSet<>();
        assertTrue(hostSet.add(first));
        assertTrue(hostSet.add(second));
        assertEquals(2, hostSet.size());

        Map<OresEntry, String> hostMap = new HashMap<>();
        hostMap.put(first, "first");
        hostMap.put(second, "second");
        assertEquals(2, hostMap.size());
        assertEquals("first", hostMap.get(first));
        assertEquals("second", hostMap.get(second));
    }

    @Test
    void hostEqualityHashingAndTextNeverInvokePayloadCallbacks() {
        Object hostile = new Object() {
            @Override public boolean equals(Object other) {
                throw new AssertionError("payload equals() invoked");
            }
            @Override public int hashCode() {
                throw new AssertionError("payload hashCode() invoked");
            }
            @Override public String toString() {
                throw new AssertionError("payload toString() invoked");
            }
        };

        OresEntry first = new OresEntry(hostile, hostile);
        OresEntry second = new OresEntry(hostile, hostile);
        assertFalse(first.equals(second));
        assertDoesNotThrow(first::hashCode);
        assertEquals("OresEntry<opaque>", assertDoesNotThrow(first::toString));
    }

    @Test
    void cyclicPayloadDoesNotRecurseThroughHostCarrierMethods() {
        List<Object> cycle = new ArrayList<>();
        OresEntry entry = new OresEntry(cycle, "secret");
        cycle.add(entry);

        assertDoesNotThrow(entry::hashCode);
        assertEquals("OresEntry<opaque>", entry.toString());
        assertFalse(entry.toString().contains("secret"));

        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(entry));
    }

    @Test
    void actorFreezeDoesNotCollapseDistinctHostEntryCarriers() {
        OresEntry first = new OresEntry("same", List.of(1, 2));
        OresEntry second = new OresEntry("same", List.of(1, 2));

        Set<Object> identitySet =
                Collections.newSetFromMap(new IdentityHashMap<>());
        identitySet.add(first);
        identitySet.add(second);

        Set<?> frozenSet =
                assertInstanceOf(Set.class, ActorRuntime.freeze(identitySet));
        assertEquals(2, frozenSet.size());

        Map<OresEntry, String> identityMap = new IdentityHashMap<>();
        identityMap.put(first, "a");
        identityMap.put(second, "b");

        Map<?, ?> frozenMap =
                assertInstanceOf(Map.class, ActorRuntime.freeze(identityMap));
        assertEquals(2, frozenMap.size());
        assertTrue(frozenMap.containsValue("a"));
        assertTrue(frozenMap.containsValue("b"));
    }
}
