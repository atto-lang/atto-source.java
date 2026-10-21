package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

        // Keep the probe dynamically typed: OresEntry is final, so Java
        // correctly rejects an impossible instanceof Map.Entry check when the
        // static operand type is OresEntry.
        Object opaqueEntry = entry;
        assertFalse(opaqueEntry instanceof java.util.Map.Entry<?, ?>,
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
    @Test
    void snapshotDetachesMappingButDeliberatelyDoesNotDeepFreezeValues() {
        OresKeyedMap<String, Object> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.ANY);
        ArrayList<Object> mutable = new ArrayList<>();
        mutable.add("before");
        map.put("payload", mutable);

        List<OresEntry> snapshot = map.nominalEntrySnapshot();
        assertSame(mutable, snapshot.getFirst().value());

        map.remove("payload");
        mutable.add("after");

        assertEquals(1, snapshot.size(),
                "mapping cardinality is detached from later Map mutation");
        assertEquals(List.of("before", "after"), snapshot.getFirst().value(),
                "value graphs stay local references in this non-transport API");
    }

    @Test
    void concurrentValueReplacementCannotCorruptNominalSnapshotOrder() throws Exception {
        OresKeyedMap<String, Integer> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        List<String> expectedKeys = java.util.stream.IntStream.range(0, 64)
                .mapToObj(i -> "k" + i)
                .toList();
        for (int i = 0; i < expectedKeys.size(); i++) {
            map.put(expectedKeys.get(i), i);
        }

        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = workers.submit(() -> {
                for (int round = 0; round < 1_000; round++) {
                    for (int i = 0; i < expectedKeys.size(); i++) {
                        map.put(expectedKeys.get(i), round + i);
                    }
                }
            });
            var reader = workers.submit(() -> {
                for (int round = 0; round < 1_000; round++) {
                    List<OresEntry> snapshot = map.nominalEntrySnapshot();
                    assertEquals(expectedKeys,
                            snapshot.stream().map(OresEntry::key).toList());
                    assertEquals(expectedKeys.size(), snapshot.size());
                    assertTrue(snapshot.stream().allMatch(entry -> entry.value() != null));
                }
            });

            writer.get(10, java.util.concurrent.TimeUnit.SECONDS);
            reader.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
    }


    @Test
    void nominalSnapshotDoesNotReenterCompilerOwnedEntryValidator() {
        java.util.concurrent.atomic.AtomicInteger validations =
                new java.util.concurrent.atomic.AtomicInteger();
        OresKeyedMap<String, Integer> map = OresKeyedMap.stringKeys(
                OresKeyedMap.ValueDomain.INT,
                (key, value) -> validations.incrementAndGet());
        map.put("x", 1);
        assertEquals(1, validations.get());

        validations.set(0);
        List<OresEntry> snapshot = map.nominalEntrySnapshot();

        assertEquals(0, validations.get(),
                "read-only snapshot construction must not re-run mutation policy");
        assertEquals(1, snapshot.size());
        assertEquals("x", snapshot.getFirst().key());
        assertEquals(1, snapshot.getFirst().value());
    }


    @Test
    void nominalSnapshotCannotAccidentallyBecomeActorTransportByComposition() {
        OresKeyedMap<String, Integer> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        map.put("x", 7);

        List<OresEntry> snapshot = map.nominalEntrySnapshot();

        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(snapshot));
        assertTrue(denied.getMessage().contains("not Sendable"),
                denied.getMessage());
        assertEquals(1, snapshot.size());
        assertEquals("x", snapshot.getFirst().key());
        assertEquals(7, snapshot.getFirst().value());
    }

    @Test
    void concurrentRemoveReinsertNeverProducesDuplicateOrMisorderedSnapshotKeys()
            throws Exception {
        OresKeyedMap<String, Integer> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        for (int i = 0; i < 32; i++) {
            map.put("fixed-" + i, i);
        }
        map.put("moving", 100);

        List<String> fixed = java.util.stream.IntStream.range(0, 32)
                .mapToObj(i -> "fixed-" + i)
                .toList();

        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = workers.submit(() -> {
                for (int i = 0; i < 2_000; i++) {
                    map.remove("moving");
                    map.put("moving", i);
                }
            });
            var reader = workers.submit(() -> {
                for (int i = 0; i < 2_000; i++) {
                    List<OresEntry> snapshot = map.nominalEntrySnapshot();
                    List<Object> keys =
                            snapshot.stream().map(OresEntry::key).toList();

                    assertEquals(keys.size(),
                            new java.util.LinkedHashSet<>(keys).size(),
                            "one atomic snapshot must never contain duplicate keys");
                    assertTrue(keys.size() == fixed.size()
                                    || keys.size() == fixed.size() + 1,
                            "snapshot cardinality must correspond to either side of the atomic remove/put");
                    assertEquals(fixed, keys.subList(0, fixed.size()),
                            "stable keys must keep insertion order");
                    if (keys.size() == fixed.size() + 1) {
                        assertEquals("moving", keys.getLast(),
                                "reinsertion must place the moving key at the end");
                    }
                }
            });

            writer.get(10, java.util.concurrent.TimeUnit.SECONDS);
            reader.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
    }


}
