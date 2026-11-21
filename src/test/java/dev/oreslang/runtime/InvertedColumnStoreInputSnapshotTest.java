package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only regression for caller-owned row and RECORD inputs.
 * This does not prove AttoLang guest lowering or cross-thread atomic snapshots
 * of a byte[] actively being mutated while the method is called.
 */
final class InvertedColumnStoreInputSnapshotTest {
    private static InvertedColumnStore makeStore() {
        return new InvertedColumnStore(
                InvertedColumnStore.ColumnSpec.record(4),
                InvertedColumnStore.ColumnSpec.scalar(InvertedColumnStore.Kind.I64));
    }

    private static void assertRow(InvertedColumnStore store,
                                  InvertedColumnStore.Handle row,
                                  byte[] record, long value) {
        assertArrayEquals(record, (byte[]) store.get(row, 0));
        assertEquals(value, store.get(row, 1));
    }

    @Test
    void pushAndUpdateDetachInputArrayCellsAndPackedRecordPayloads() {
        try (var store = makeStore()) {
            byte[] packed = {1, 2, 3, 4};
            Object[] row = {packed, 7L};
            var handle = store.push(row);
            packed[0] = 99;
            row[0] = new byte[]{9, 9, 9, 9};
            row[1] = "not a long";
            assertRow(store, handle, new byte[]{1, 2, 3, 4}, 7L);

            byte[] replacement = {5, 6, 7, 8};
            Object[] next = {replacement, 11L};
            store.updateRow(handle, next);
            replacement[1] = 99;
            next[0] = null;
            next[1] = 123;
            assertRow(store, handle, new byte[]{5, 6, 7, 8}, 11L);

            assertThrows(IllegalArgumentException.class,
                    () -> store.updateRow(handle, new Object[]{new byte[]{0, 1, 2, 3}, "bad"}));
            assertRow(store, handle, new byte[]{5, 6, 7, 8}, 11L);
        }
    }

    @Test
    void individualWritesAndLoanWritesAlsoDetachPackedRecords() {
        try (var store = makeStore()) {
            var handle = store.push(new byte[]{1, 2, 3, 4}, 1L);
            byte[] newPacked = {9, 8, 7, 6};
            store.set(handle, 0, newPacked);
            newPacked[0] = 99;
            assertRow(store, handle, new byte[]{9, 8, 7, 6}, 1L);

            try (var loan = store.borrowWrite(0)) {
                byte[] loanInput = {4, 3, 2, 1};
                loan.set(0, loanInput);
                loanInput[1] = 99;
                assertArrayEquals(new byte[]{4, 3, 2, 1}, (byte[]) loan.get(0));
            }
            assertRow(store, handle, new byte[]{4, 3, 2, 1}, 1L);
        }
    }

    @Test
    void compilerVerifiedTransformDetachesCallbackOwnedRecordResult() {
        try (var store = makeStore()) {
            var handle = store.push(new byte[]{0, 0, 0, 0}, 9L);
            byte[] returned = {6, 5, 4, 3};
            Object[] result = {returned, 33L};
            store.transformRow(handle, snapshot -> {
                assertArrayEquals(new byte[]{0, 0, 0, 0}, (byte[]) snapshot[0]);
                return result;
            });
            returned[0] = 99;
            result[1] = "bad";
            assertRow(store, handle, new byte[]{6, 5, 4, 3}, 33L);
        }
    }

    @Test
    void wrongTypedRowDoesNotPartiallyModifyExistingColumns() {
        try (var store = makeStore()) {
            var handle = store.push(new byte[]{1, 1, 1, 1}, 5L);
            var before = store.size();
            assertThrows(IllegalArgumentException.class,
                    () -> store.push(new Object[]{new byte[]{2, 2, 2, 2}, new Object()}));
            assertEquals(before, store.size());
            assertRow(store, handle, new byte[]{1, 1, 1, 1}, 5L);
        }
    }
}
