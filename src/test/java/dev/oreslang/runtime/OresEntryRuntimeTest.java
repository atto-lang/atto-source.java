package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class OresEntryRuntimeTest {
    @Test
    void freezePreservesNominalEntryAndDetachesNestedValues() {
        ArrayList<Object> mutableKey = new ArrayList<>();
        mutableKey.add("k");
        OresEntry source = new OresEntry(mutableKey, List.of("v"));

        Object frozenValue = ActorRuntime.freeze(source);
        OresEntry frozen = assertInstanceOf(OresEntry.class, frozenValue);

        assertNotSame(source, frozen);
        assertNotSame(mutableKey, frozen.key());
        assertEquals(List.of("k"), frozen.key());
        mutableKey.add("late");
        assertEquals(List.of("k"), frozen.key());

        @SuppressWarnings("unchecked")
        List<Object> frozenKey = (List<Object>) frozen.key();
        assertThrows(UnsupportedOperationException.class, () -> frozenKey.add("mutate"));
    }

    @Test
    void entrySharedStateChildrenExposeExactlyKeyAndValue() {
        OresEntry entry = new OresEntry("left", "right");
        assertEquals(List.of("left", "right"), entry.sharedStateChildren());
    }
    @Test
    void hostObjectOperationsNeverTraverseCyclicEntryPayloads() {
        ArrayList<Object> cyclic = new ArrayList<>();
        OresEntry entry = new OresEntry(cyclic, "payload");
        cyclic.add(entry);

        assertEquals("OresEntry<opaque>", entry.toString());
        assertEquals(System.identityHashCode(entry), entry.hashCode());
        assertSame(entry, entry);
        assertNotEquals(entry, new OresEntry(cyclic, "payload"));

        // Guest-language value equality is handled by the evaluator, not
        // Java Object.equals; no recursive Java hash/equals is permitted.
        assertEquals(cyclic, entry.key());
        assertEquals("payload", entry.value());
    }

    @Test
    void hostEqualsNeverConfusesDistinctNominalEntryInstances() {
        OresEntry left = new OresEntry(1L, "one");
        OresEntry right = new OresEntry(1L, "one");
        assertNotEquals(left, right);
        assertEquals("OresEntry<opaque>", right.toString());
    }

    @Test
    void hostConstructionRejectsNullComponents() {
        assertThrows(NullPointerException.class, () -> new OresEntry(null, "value"));
        assertThrows(NullPointerException.class, () -> new OresEntry("key", null));
    }

}
