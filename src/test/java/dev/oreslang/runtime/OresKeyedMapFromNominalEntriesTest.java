package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class OresKeyedMapFromNominalEntriesTest {
    @Test
    void emptyAndDistinctEntriesConstructPrivateTypedStorage() {
        OresKeyedMap<Object, Object> empty = OresKeyedMap.fromNominalEntries(
                List.of(), OresKeyedMap.KeyDomain.STRING,
                OresKeyedMap.ValueDomain.INT);
        assertTrue(empty.isEmpty());

        OresKeyedMap<Object, Object> map = OresKeyedMap.fromNominalEntries(
                List.of(new OresEntry("a", 1), new OresEntry("b", 2)),
                OresKeyedMap.KeyDomain.STRING, OresKeyedMap.ValueDomain.INT);
        assertEquals(2, map.size());
        assertEquals(1, map.get("a"));
        assertEquals(2, map.get("b"));
        assertEquals(List.of("a", "b"),
                map.entrySnapshot().stream().map(java.util.Map.Entry::getKey).toList());
    }

    @Test
    void duplicateEntryKeysFailInsteadOfChoosingFirstOrLast() {
        OresKeyedMap.DuplicateKeyException duplicate = assertThrows(
                OresKeyedMap.DuplicateKeyException.class,
                () -> OresKeyedMap.fromNominalEntries(
                        List.of(new OresEntry("secret", 1), new OresEntry("secret", 2)),
                        OresKeyedMap.KeyDomain.STRING, OresKeyedMap.ValueDomain.INT));
        assertFalse(duplicate.getMessage().contains("secret"),
                "duplicate key errors must not reveal private key contents");
    }

    @Test
    void symbolKeysNormalizeLogicallyButStringAndSymbolStayDistinct() {
        OresKeyedMap<Object, Object> mixed = OresKeyedMap.fromNominalEntries(
                List.of(
                        new OresEntry("ready", 1),
                        new OresEntry(OresSymbol.process("ready"), 2),
                        new OresEntry("#ready", 3),
                        new OresEntry(OresSymbol.stable(
                                "550e8400-e29b-41d4-a716-446655440000"), 4)),
                OresKeyedMap.KeyDomain.STRING_OR_SYMBOL,
                OresKeyedMap.ValueDomain.INT);
        assertEquals(4, mixed.size());
        assertEquals(1, mixed.get("ready"));
        assertEquals(2, mixed.get(OresSymbol.process("ready")));
        assertEquals(3, mixed.get("#ready"));

        assertThrows(OresKeyedMap.DuplicateKeyException.class,
                () -> OresKeyedMap.fromNominalEntries(
                        List.of(
                                new OresEntry(OresSymbol.process("cafe\u0301"), 1),
                                new OresEntry(OresSymbol.process("café"), 2)),
                        OresKeyedMap.KeyDomain.SYMBOL,
                        OresKeyedMap.ValueDomain.INT));
    }

    @Test
    void malformedProductsAndWrongDomainsFailBeforePublish() {
        assertThrows(IllegalArgumentException.class,
                () -> OresKeyedMap.fromNominalEntries(
                        List.of(new OresEntry("a", 1), java.util.Map.entry("b", 2)),
                        OresKeyedMap.KeyDomain.STRING, OresKeyedMap.ValueDomain.INT));
        assertThrows(IllegalArgumentException.class,
                () -> OresKeyedMap.fromNominalEntries(
                        List.of(new OresEntry(1, 2)),
                        OresKeyedMap.KeyDomain.STRING, OresKeyedMap.ValueDomain.INT));
        assertThrows(IllegalArgumentException.class,
                () -> OresKeyedMap.fromNominalEntries(
                        List.of(new OresEntry("a", 1), new OresEntry("b", "wrong")),
                        OresKeyedMap.KeyDomain.STRING, OresKeyedMap.ValueDomain.INT));
    }

    @Test
    void boundedInputCannotStreamMoreThanMaximumEntries() {
        AtomicInteger traversed = new AtomicInteger();
        Iterable<Object> unbounded = () -> new Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public Object next() {
                int n = traversed.getAndIncrement();
                return new OresEntry("key-" + n, n);
            }
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresKeyedMap.fromNominalEntries(
                        unbounded, OresKeyedMap.KeyDomain.STRING,
                        OresKeyedMap.ValueDomain.INT));
        assertTrue(error.getMessage().contains("maximum key count"),
                error.getMessage());
        assertEquals(OresKeyedMap.MAX_ENTRIES, traversed.get(),
                "do not request the unbounded iterable's next entry after quota");
    }

    @Test
    void rejectsOversizedKeyAndAggregateWireBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> OresKeyedMap.fromNominalEntries(
                        List.of(new OresEntry("x".repeat(
                                OresKeyedMap.MAX_TEXT_KEY_BYTES + 1), 1)),
                        OresKeyedMap.KeyDomain.STRING, OresKeyedMap.ValueDomain.INT));

        List<OresEntry> entries = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            entries.add(new OresEntry(
                    "x".repeat(OresKeyedMap.MAX_TEXT_KEY_BYTES - 3)
                            + String.format("%03d", i), i));
        }
        assertThrows(IllegalArgumentException.class,
                () -> OresKeyedMap.fromNominalEntries(
                        entries, OresKeyedMap.KeyDomain.STRING,
                        OresKeyedMap.ValueDomain.INT));
    }

    @Test
    void inputIsNotADeepFreezeOrAnActorSendabilityProof() {
        List<Integer> value = new ArrayList<>(List.of(1, 2));
        OresKeyedMap<Object, Object> map = OresKeyedMap.fromNominalEntries(
                List.of(new OresEntry("list", value)),
                OresKeyedMap.KeyDomain.STRING, OresKeyedMap.ValueDomain.ANY);
        value.add(3);
        assertEquals(List.of(1, 2, 3), map.get("list"),
                "host foundation must not misrepresent mutable nested values as frozen");
    }
    @Test
    void mutableTypedMapCannotCrossActorBoundariesEvenInsideNominalEntry() {
        OresKeyedMap<Object, Object> map = OresKeyedMap.fromNominalEntries(
                List.of(new OresEntry("a", 1)),
                OresKeyedMap.KeyDomain.STRING, OresKeyedMap.ValueDomain.INT);
        IllegalArgumentException direct = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(map));
        assertTrue(direct.getMessage().contains("typed guest Map transport"),
                direct.getMessage());

        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(new OresEntry("payload", map)));
        assertTrue(nested.getMessage().contains("typed guest Map transport"),
                nested.getMessage());
    }


}
