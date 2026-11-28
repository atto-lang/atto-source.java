package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Host identity is never guest Entry value equality. Does not claim runtime lowering. */
final class OresEntryHostIdentityForwardPortTest {
    @Test void equalContentsNeverCollapseDistinctCarrierIdentities() {
        var a = new OresEntry("key", List.of(1,2));
        var b = new OresEntry("key", List.of(1,2));
        assertNotEquals(a,b);
        Set<OresEntry> entries = new HashSet<>();
        assertTrue(entries.add(a));
        assertTrue(entries.add(b));
        assertEquals(2,entries.size());
        Map<OresEntry,String> indexed = new HashMap<>();
        indexed.put(a,"a"); indexed.put(b,"b");
        assertEquals(2,indexed.size());
    }
    @Test void hostilePayloadCannotRunHostEqualsHashOrLogging() {
        Object hostile = new Object() {
            @Override public boolean equals(Object other) {throw new AssertionError("host equals");}
            @Override public int hashCode() {throw new AssertionError("host hash");}
            @Override public String toString() {throw new AssertionError("host toString");}
        };
        var a = new OresEntry(hostile,hostile);
        var b = new OresEntry(hostile,hostile);
        assertNotEquals(a,b);
        assertEquals(System.identityHashCode(a),a.hashCode());
        assertEquals("OresEntry<opaque>",a.toString());
    }
    @Test void selfReferentialMutableChildDoesNotRecurseThroughHostCarrier() {
        var cyclic = new ArrayList<Object>();
        var value = new OresEntry(cyclic,"private");
        cyclic.add(value);
        assertEquals("OresEntry<opaque>",value.toString());
        assertEquals(List.of(cyclic,"private"),value.sharedStateChildren());
    }
}
