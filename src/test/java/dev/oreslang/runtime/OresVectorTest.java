package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class OresVectorTest {
    @Test
    void enforcesCapacityForAddPushIndexedInsertAndBulkAdd() {
        OresVector vector = new OresVector(3, 1);
        vector.push(11);
        vector.add(22);
        vector.add(0, 0);
        assertEquals(List.of(0, 11, 22), vector);
        assertEquals(3, vector.size());
        assertThrows(IllegalArgumentException.class, () -> vector.push(33));
        assertThrows(IllegalArgumentException.class, () -> vector.add(1, 33));
        assertThrows(IllegalArgumentException.class, () -> vector.addAll(List.of(33)));
        assertThrows(IllegalArgumentException.class, () -> vector.addAll(1, List.of(33)));
        assertEquals(List.of(0, 11, 22), vector);
        assertEquals(3, vector.reservedCapacity());
        assertEquals(22, vector.pop());
        assertEquals(List.of(0, 11), vector);
    }

    @Test
    void failedReservationPreservesLogicalCapacityAndElements() {
        OresVector vector = new OresVector(5, 1);
        vector.add(42);
        int before = vector.reservedCapacity();
        assertThrows(IllegalArgumentException.class, () -> vector.reserve(6));
        assertThrows(IllegalArgumentException.class, () -> vector.ensureCapacity(-1));
        assertEquals(before, vector.reservedCapacity());
        assertEquals(List.of(42), vector);
        vector.reserve(5);
        assertEquals(5, vector.reservedCapacity());
    }

    @Test
    void zeroCapacityAndContradictoryMetadataFailClosed() {
        OresVector zero = new OresVector(0, 0);
        assertThrows(IllegalArgumentException.class, () -> zero.push(1));
        assertTrue(zero.isEmpty());
        // Host-level pop on an empty vector is a state error; the guest
        // Vector.pop lowering returns Option.None instead of calling it.
        assertThrows(IllegalStateException.class, zero::pop);
        assertThrows(IllegalArgumentException.class, () -> new OresVector(1, 2));
        assertThrows(IllegalArgumentException.class, () -> new OresVector(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new OresVector(2, -1));
        assertThrows(IllegalArgumentException.class, () -> new OresVector(Integer.MAX_VALUE, 0));
    }

    @Test
    void mutableListAliasesCannotBypassQuota() {
        OresVector vector = new OresVector(2, 0);
        List<Object> alias = vector;
        alias.add(1);
        alias.add(2);
        assertThrows(IllegalArgumentException.class, () -> alias.add(3));
        assertThrows(IllegalArgumentException.class, () -> alias.addAll(List.of(3)));
        assertEquals(List.of(1, 2), alias);
        alias.remove(0);
        alias.add(3);
        assertEquals(List.of(2, 3), vector);

        ArrayList<Object> raw = vector;
        assertThrows(IllegalArgumentException.class, () -> raw.ensureCapacity(4));
        assertEquals(2, vector.size());
    }
}
