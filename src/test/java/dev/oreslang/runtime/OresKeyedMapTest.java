package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class OresKeyedMapTest {
    @Test void symbolKeysUseLogicalIdentityNotInterningOrHostAddress() {
        OresKeyedMap<OresSymbol, Integer> map = OresKeyedMap.symbolKeys();
        OresSymbol first = OresSymbol.process("cafe\u0301");
        OresSymbol equivalent = OresSymbol.process("café");
        assertNotSame(first, equivalent);
        assertNull(map.put(first, 7));
        assertEquals(7, map.get(equivalent));
        assertEquals(7, map.put(equivalent, 8));
        assertEquals(1, map.size());
        assertEquals(8, map.get(first));
        assertEquals(8, map.remove(equivalent));
        assertTrue(map.isEmpty());
    }

    @Test void distinctNamespacesCoexistOnlyInExplicitMixedDomain() {
        OresKeyedMap<Object, Integer> map = OresKeyedMap.stringOrSymbolKeys();
        OresSymbol process = OresSymbol.process("ready");
        OresSymbol stable = OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000");
        OresSymbol sameTextProcess = OresSymbol.process(stable.name());
        map.put("ready", 1);
        map.put(process, 2);
        map.put("#ready", 3);
        map.put(stable, 4);
        map.put(sameTextProcess, 5);
        assertEquals(5, map.size());
        assertEquals(1, map.get("ready"));
        assertEquals(2, map.get(OresSymbol.process("ready")));
        assertEquals(3, map.get("#ready"));
        assertEquals(4, map.get(OresSymbol.stable(stable.name())));
        assertEquals(5, map.get(sameTextProcess));
        assertEquals(List.of("ready", process, "#ready", stable, sameTextProcess),
                map.entrySnapshot().stream().map(Map.Entry::getKey).toList());
    }

    @Test void wrongKeyDomainsAreRejectedInsteadOfCoerced() {
        OresKeyedMap<OresSymbol, Integer> symbols = OresKeyedMap.symbolKeys();
        OresKeyedMap<String, Integer> strings = OresKeyedMap.stringKeys();
        @SuppressWarnings("rawtypes")
        OresKeyedMap rawSymbols = symbols;
        assertThrows(IllegalArgumentException.class, () -> rawSymbols.put("ready", 1));
        assertThrows(IllegalArgumentException.class, () -> strings.containsKey(OresSymbol.process("ready")));
        assertThrows(IllegalArgumentException.class, () -> strings.get(OresSymbol.process("ready")));
        assertThrows(NullPointerException.class, () -> strings.put(null, 1));
        assertThrows(NullPointerException.class, () -> strings.put("ready", null));
        assertThrows(IllegalArgumentException.class, () ->
                OresKeyedMap.stringOrSymbolKeys().put(123, "bad"));
        assertEquals(0, symbols.size());
        assertEquals(0, strings.size());
    }

    @Test void reifiedValueDomainsRejectWrongRuntimeValues() {
        OresKeyedMap<OresSymbol, Object> integers =
                OresKeyedMap.symbolKeys(OresKeyedMap.ValueDomain.INT);
        OresSymbol key = OresSymbol.process("ready");
        integers.put(key, 17L);
        integers.put(key, java.math.BigInteger.valueOf(20));
        assertThrows(IllegalArgumentException.class, () -> integers.put(key, "wrong"));
        assertThrows(IllegalArgumentException.class, () -> integers.put(key, 1.0));
        assertEquals(java.math.BigInteger.valueOf(20), integers.get(key));
        assertEquals(1, integers.size());

        OresKeyedMap<String, Object> booleans =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.BOOL);
        booleans.put("ok", Boolean.TRUE);
        assertThrows(IllegalArgumentException.class, () -> booleans.put("ok", 1));
        assertEquals(Boolean.TRUE, booleans.get("ok"));

        OresKeyedMap<OresSymbol, Object> symbols =
                OresKeyedMap.symbolKeys(OresKeyedMap.ValueDomain.SYMBOL);
        symbols.put(key, OresSymbol.process("value"));
        assertThrows(IllegalArgumentException.class, () -> symbols.put(key, "value"));

        OresKeyedMap<String, Object> text =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.STRING);
        text.put("status", "#ready");
        assertThrows(IllegalArgumentException.class,
                () -> text.put("status", OresSymbol.process("ready")));
    }

    @Test void invalidValueInBatchNeverCommitsEarlierEntries() {
        OresKeyedMap<String, Object> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        map.put("existing", 5);
        LinkedHashMap<String, Object> batch = new LinkedHashMap<>();
        batch.put("valid", 7L);
        batch.put("invalid", "not an int");
        assertThrows(IllegalArgumentException.class, () -> map.putAll(batch));
        assertEquals(Map.of("existing", 5), map.shallowSnapshot());
    }

    @Test void mutableMapsAreNotSendableActorMessages() {
        OresKeyedMap<OresSymbol, Integer> map =
                OresKeyedMap.symbolKeys(OresKeyedMap.ValueDomain.INT);
        map.put(OresSymbol.process("ready"), 1);
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(map));
        assertThrows(IllegalArgumentException.class, () ->
                ActorRuntime.freeze(List.of(Map.of("embedded", map))));
    }

    @Test void concurrentWritesCannotLoseKeysOrCorruptIteration() throws Exception {
        OresKeyedMap<String, Integer> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        java.util.concurrent.ExecutorService workers =
                java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Callable<Void>> jobs = new java.util.ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                final int identity = worker;
                jobs.add(() -> {
                    for (int index = 0; index < 250; index++) {
                        String key = "worker" + identity + "-" + index;
                        map.put(key, index);
                        assertEquals(index, map.get(key));
                    }
                    return null;
                });
            }
            for (var future : workers.invokeAll(jobs)) {
                future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals(2_000, map.size());
            assertEquals(2_000, map.entrySnapshot().size());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test void mapRejectsOversizedAndMalformedUtf8Keys() {
        OresKeyedMap<String, Integer> map = OresKeyedMap.stringKeys();
        map.put("x".repeat(OresKeyedMap.MAX_TEXT_KEY_BYTES), 1);
        assertThrows(IllegalArgumentException.class, () ->
                map.put("x".repeat(OresKeyedMap.MAX_TEXT_KEY_BYTES + 1), 2));
        assertThrows(IllegalArgumentException.class, () ->
                map.put("\uD800", 3));
        assertThrows(IllegalArgumentException.class, () ->
                map.put("🚀".repeat(4097), 4));
        assertEquals(1, map.size());
    }

    @Test void mapCapacityFailsBeforeInsertionIncludingAtomicBatch() {
        OresKeyedMap<String, Integer> map = OresKeyedMap.stringKeys();
        for (int i = 0; i < OresKeyedMap.MAX_ENTRIES - 1; i++) {
            map.put("key" + i, i);
        }
        assertThrows(IllegalArgumentException.class, () ->
                map.putAll(Map.of("next", 1, "overflow", 2)));
        assertFalse(map.containsKey("next"));
        assertFalse(map.containsKey("overflow"));
        map.put("last", 3);
        assertEquals(OresKeyedMap.MAX_ENTRIES, map.size());
        assertThrows(IllegalArgumentException.class, () -> map.put("too-many", 4));
        assertEquals(3, map.put("last", 5));
        assertEquals(5, map.get("last"));
    }

    @Test void putAllRejectsEntireInvalidBatchBeforeMutation() {
        OresKeyedMap<String, Integer> strings = OresKeyedMap.stringKeys();
        strings.put("already", 7);
        LinkedHashMap<Object, Integer> invalid = new LinkedHashMap<>();
        invalid.put("good", 8);
        invalid.put(OresSymbol.process("bad"), 9);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<? extends String, ? extends Integer> unchecked = (Map) invalid;
        assertThrows(IllegalArgumentException.class, () -> strings.putAll(unchecked));
        assertEquals(Map.of("already", 7), strings.shallowSnapshot());
        invalid.remove(OresSymbol.process("bad"));
        invalid.put("fine", 9);
        strings.putAll(unchecked);
        assertEquals(List.of("already", "good", "fine"),
                strings.entrySnapshot().stream().map(Map.Entry::getKey).toList());
    }

    @Test void batchRejectsDuplicateNominalSymbolKeysFromIdentityBasedMap() {
        OresKeyedMap<OresSymbol, Integer> map = OresKeyedMap.symbolKeys();
        OresSymbol existing = OresSymbol.process("already");
        map.put(existing, 9);
        java.util.IdentityHashMap<OresSymbol, Integer> batch =
                new java.util.IdentityHashMap<>();
        OresSymbol composed = OresSymbol.process("café");
        OresSymbol decomposed = OresSymbol.process("café");
        assertNotSame(composed, decomposed);
        assertEquals(composed, decomposed);
        batch.put(composed, 1);
        batch.put(decomposed, 2);
        assertEquals(2, batch.size(), "identity map should expose both distinct Java keys");

        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class, () -> map.putAll(batch));
        assertTrue(denied.getMessage().contains("duplicate logical key"),
                denied.getMessage());
        assertEquals(Map.of(existing, 9), map.shallowSnapshot(),
                "a failed batch must not modify previously stored entries");
    }

    @Test void exportedViewsCannotMutateTheInternalMapping() {
        OresKeyedMap<OresSymbol, Integer> map = OresKeyedMap.symbolKeys();
        map.put(OresSymbol.process("a"), 1);
        Map<OresSymbol, Integer> snapshot = map.shallowSnapshot();
        List<Map.Entry<OresSymbol, Integer>> entries = map.entrySnapshot();
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put(OresSymbol.process("b"), 2));
        assertThrows(UnsupportedOperationException.class,
                () -> entries.getFirst().setValue(3));
        assertThrows(UnsupportedOperationException.class,
                () -> entries.add(Map.entry(OresSymbol.process("c"), 3)));
        map.put(OresSymbol.process("b"), 2);
        assertEquals(1, snapshot.size());
        assertEquals(1, entries.size());
        assertEquals(2, map.size());
    }
}
