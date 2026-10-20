package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Java record carrier must not define Oreslang equality, hashing,
 * serialization, or diagnostic formatting policies.
 */
final class OresEntryHostCarrierBoundaryTest {
    @Test
    void hostContainersCannotConflateDistinctNominalEntryCarriers() {
        OresEntry first = new OresEntry("key", List.of(1, 2));
        OresEntry second = new OresEntry("key", List.of(1, 2));

        assertSame(first, first);
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
    void hostEqualityAndHashingNeverInvokePayloadCallbacks() {
        Object hostile = new Object() {
            @Override public boolean equals(Object other) {
                throw new AssertionError("guest payload host equals() invoked");
            }
            @Override public int hashCode() {
                throw new AssertionError("guest payload host hashCode() invoked");
            }
            @Override public String toString() {
                throw new AssertionError("guest payload host toString() invoked");
            }
        };

        OresEntry a = new OresEntry(hostile, hostile);
        OresEntry b = new OresEntry(hostile, hostile);
        assertFalse(a.equals(b));
        assertEquals(System.identityHashCode(a), a.hashCode());
        assertEquals("OresEntry<opaque>", a.toString());
    }

    @Test
    void cyclicMutablePayloadDoesNotRecurseThroughHostRecordMethods() {
        List<Object> key = new ArrayList<>();
        OresEntry first = new OresEntry(key, "secret-value");
        key.add(first);
        List<Object> otherKey = new ArrayList<>();
        OresEntry second = new OresEntry(otherKey, "secret-value");
        otherKey.add(second);

        assertNotEquals(first, second);
        assertEquals(System.identityHashCode(first), first.hashCode());
        assertEquals("OresEntry<opaque>", first.toString());
        assertFalse(first.toString().contains("secret-value"));

        // Identity-safe host carriers do not authorize cyclic actor transport.
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(first));
    }
    @Test
    void actorFreezeNeverSilentlyCollapsesDistinctHostEntryCarriers() {
        OresEntry first = new OresEntry("same", List.of(1, 2));
        OresEntry second = new OresEntry("same", List.of(1, 2));
        Set<Object> identitySet = Collections.newSetFromMap(new IdentityHashMap<>());
        identitySet.add(first);
        identitySet.add(second);

        Set<?> frozenSet = assertInstanceOf(Set.class, ActorRuntime.freeze(identitySet));
        assertEquals(2, frozenSet.size(),
                "transport must not coalesce host-distinct Entry carriers");

        Map<OresEntry, String> identityMap = new IdentityHashMap<>();
        identityMap.put(first, "a");
        identityMap.put(second, "b");
        Map<?, ?> frozenMap = assertInstanceOf(Map.class, ActorRuntime.freeze(identityMap));
        assertEquals(2, frozenMap.size(),
                "transport must not coalesce host-distinct Entry keys");
        assertTrue(frozenMap.containsValue("a"));
        assertTrue(frozenMap.containsValue("b"));
    }

}
