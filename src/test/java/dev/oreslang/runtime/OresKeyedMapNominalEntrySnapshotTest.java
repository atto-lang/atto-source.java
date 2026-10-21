package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class OresKeyedMapNominalEntrySnapshotTest {
    @Test
    void nominalSnapshotIsInsertionOrderedAndDetachedFromLaterMutation() {
        OresKeyedMap<String, Integer> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        map.put("a", 1);
        map.put("b", 2);

        List<OresEntry> first = map.nominalEntrySnapshot();
        assertEquals(List.of("a", "b"),
                first.stream().map(OresEntry::key).toList());
        assertEquals(List.of(1, 2),
                first.stream().map(OresEntry::value).toList());

        // Replacing an existing key keeps its original insertion slot.
        map.put("a", 10);
        List<OresEntry> replaced = map.nominalEntrySnapshot();
        assertEquals(List.of("a", "b"),
                replaced.stream().map(OresEntry::key).toList());
        assertEquals(List.of(10, 2),
                replaced.stream().map(OresEntry::value).toList());

        // The earlier snapshot remains detached from the mapping mutation.
        assertEquals(List.of(1, 2),
                first.stream().map(OresEntry::value).toList());

        // Removing and reinserting gives the key a fresh slot at the end.
        assertEquals(10, map.remove("a"));
        map.put("a", 11);
        List<OresEntry> reinserted = map.nominalEntrySnapshot();
        assertEquals(List.of("b", "a"),
                reinserted.stream().map(OresEntry::key).toList());
        assertEquals(List.of(2, 11),
                reinserted.stream().map(OresEntry::value).toList());
    }

    @Test
    void nominalSnapshotPreservesStringAndSymbolNamespaces() {
        OresKeyedMap<Object, Integer> map =
                OresKeyedMap.stringOrSymbolKeys(OresKeyedMap.ValueDomain.INT);
        OresSymbol symbol = OresSymbol.process("ready");
        map.put("ready", 1);
        map.put(symbol, 2);

        List<OresEntry> snapshot = map.nominalEntrySnapshot();
        assertEquals(2, snapshot.size());
        assertEquals("ready", snapshot.get(0).key());
        assertSame(symbol, snapshot.get(1).key());
        assertEquals(1, snapshot.get(0).value());
        assertEquals(2, snapshot.get(1).value());
    }

    @Test
    void snapshotListAndEntryCarriersExposeNoMutableHostEntrySurface() {
        OresKeyedMap<String, Integer> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        map.put("x", 7);

        List<OresEntry> snapshot = map.nominalEntrySnapshot();
        OresEntry entry = snapshot.getFirst();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new OresEntry("y", 8)));
        assertEquals("x", entry.key());
        assertEquals(7, entry.value());
        assertEquals("OresEntry<opaque>", entry.toString());
        assertFalse(entry instanceof java.util.Map.Entry<?, ?>,
                "guest Entry must never leak Java Map.Entry mutation APIs");
    }

    @Test
    void snapshotConstructionDoesNotInvokeStoredValueHostCallbacks() {
        Object hostile = new Object() {
            @Override public boolean equals(Object other) {
                throw new AssertionError("snapshot must not invoke value equals");
            }
            @Override public int hashCode() {
                throw new AssertionError("snapshot must not invoke value hashCode");
            }
            @Override public String toString() {
                throw new AssertionError("snapshot must not invoke value toString");
            }
        };

        OresKeyedMap<String, Object> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.ANY);
        map.put("opaque", hostile);

        List<OresEntry> snapshot =
                assertDoesNotThrow(map::nominalEntrySnapshot);
        assertEquals(1, snapshot.size());
        assertSame(hostile, snapshot.getFirst().value());
    }

    @Test
    void emptySnapshotIsFiniteImmutableAndStable() {
        OresKeyedMap<String, Integer> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);

        List<OresEntry> snapshot = map.nominalEntrySnapshot();
        assertTrue(snapshot.isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new OresEntry("x", 1)));

        map.put("later", 1);
        assertTrue(snapshot.isEmpty(),
                "a detached empty snapshot must not become a live view");
    }
}
