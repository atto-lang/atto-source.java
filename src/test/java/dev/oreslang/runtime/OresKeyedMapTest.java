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
