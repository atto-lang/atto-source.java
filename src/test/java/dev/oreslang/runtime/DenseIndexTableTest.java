package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DenseIndexTableTest {
    private static final class EntityTag { }
    private static final class ComponentTag { }

    @Test
    void compactWordStaysFourBytesWhileIdentityMetadataStaysOutOfLine() {
        try (DenseIndexTable<EntityTag> table = new DenseIndexTable<>(4)) {
            DenseIndex32<EntityTag> zero = table.allocate();
            DenseIndex32<EntityTag> one = table.allocate();
            assertEquals(1, zero.encodedWord());
            assertEquals(0L, zero.index());
            assertEquals(2, one.encodedWord());
            assertEquals(1L, one.index());
            assertEquals(Integer.BYTES, DenseIndex32.storageWordBytes());
            assertTrue(table.isLive(zero));
            assertEquals(1, table.checkedStorageWord(zero).orElseThrow());
        }
    }

    @Test
    void staleHandleCannotResolveAfterSlotReuse() {
        try (DenseIndexTable<EntityTag> table = new DenseIndexTable<>(1)) {
            DenseIndex32<EntityTag> stale = table.allocate();
            assertTrue(table.remove(stale));
            DenseIndex32<EntityTag> fresh = table.allocate();
            assertEquals(stale.encodedWord(), fresh.encodedWord(),
                    "the hot 32-bit row word can be reused");
            assertNotEquals(stale, fresh);
            assertFalse(table.isLive(stale));
            assertTrue(table.isLive(fresh));
            assertTrue(table.checkedStorageWord(stale).isEmpty());
        }
    }

    @Test
    void handlesCannotCrossTablesEvenWhenCompactWordsMatch() {
        try (DenseIndexTable<EntityTag> first = new DenseIndexTable<>(2);
             DenseIndexTable<EntityTag> second = new DenseIndexTable<>(2)) {
            DenseIndex32<EntityTag> a = first.allocate();
            DenseIndex32<EntityTag> b = second.allocate();
            assertEquals(a.encodedWord(), b.encodedWord());
            assertTrue(first.isLive(a));
            assertFalse(first.isLive(b));
            assertFalse(second.isLive(a));
            assertTrue(second.isLive(b));
        }
    }

    @Test
    void genericTagSeparatesDifferentLogicalTablesAtCompileTime() {
        try (DenseIndexTable<EntityTag> entities = new DenseIndexTable<>(2);
             DenseIndexTable<ComponentTag> components = new DenseIndexTable<>(2)) {
            DenseIndex32<EntityTag> entity = entities.allocate();
            DenseIndex32<ComponentTag> component = components.allocate();
            assertEquals(entity.encodedWord(), component.encodedWord());
            assertNotEquals(entity, component);
        }
    }

    @Test
    void clearInvalidatesEveryPriorHandleByEpoch() {
        try (DenseIndexTable<EntityTag> table = new DenseIndexTable<>(2)) {
            DenseIndex32<EntityTag> old = table.allocate();
            long epoch = table.epoch();
            table.clear();
            assertEquals(epoch + 1, table.epoch());
            DenseIndex32<EntityTag> fresh = table.allocate();
            assertEquals(old.encodedWord(), fresh.encodedWord());
            assertFalse(table.isLive(old));
            assertTrue(table.isLive(fresh));
        }
    }

    @Test
    void closedTableRejectsEveryHandle() {
        DenseIndexTable<EntityTag> table = new DenseIndexTable<>(2);
        DenseIndex32<EntityTag> handle = table.allocate();
        table.close();
        assertFalse(table.isLive(handle));
        assertTrue(table.checkedStorageWord(handle).isEmpty());
        assertThrows(IllegalStateException.class, table::allocate);
        assertThrows(IllegalStateException.class, table::clear);
    }

    @Test
    void capacityAndNullAreFailClosed() {
        try (DenseIndexTable<EntityTag> table = new DenseIndexTable<>(1)) {
            DenseIndex32<EntityTag> handle = table.allocate();
            assertFalse(table.isLive(null));
            assertThrows(IllegalStateException.class, table::allocate);
            assertTrue(table.remove(handle));
            assertEquals(0, table.size());
            assertEquals(handle.encodedWord(), table.allocate().encodedWord());
        }
        assertThrows(IllegalArgumentException.class, () -> new DenseIndexTable<>(0));
        assertThrows(IllegalArgumentException.class, () -> new DenseIndexTable<>(1_000_001));
    }
}
