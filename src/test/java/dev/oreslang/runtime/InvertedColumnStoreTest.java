package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class InvertedColumnStoreTest {
    @Test
    void independentTypedColumnsAndGenerationalRows() {
        try (var store = new InvertedColumnStore(
                InvertedColumnStore.Kind.F32,
                InvertedColumnStore.Kind.I64,
                InvertedColumnStore.Kind.BOOL)) {
            var first = store.push(1.5f, 10L, true);
            var middle = store.push(2.5f, 20L, false);
            var last = store.push(3.5f, 30L, true);
            assertEquals(3, store.size());
            assertEquals(1.5f, store.get(first, 0));
            assertArrayEquals(new Object[]{3.5f, 30L, true}, store.snapshot(last));
            store.eraseSwap(middle);
            assertEquals(2, store.size());
            assertEquals(3.5f, store.get(last, 0));
            assertThrows(IllegalArgumentException.class, () -> store.get(middle, 0));
            var recycled = store.push(7.0f, 70L, false);
            assertEquals(middle.slot(), recycled.slot());
            assertNotEquals(middle.generation(), recycled.generation());
            assertThrows(IllegalArgumentException.class, () -> store.set(middle, 0, 8.0f));
            store.updateRow(last, 6.0f, 60L, false);
            assertArrayEquals(new Object[]{6.0f, 60L, false}, store.snapshot(last));
        }
    }

    @Test
    void rejectsForeignStaleMalformedAndWrongTypedValuesWithoutPartialMutation() {
        try (var a = new InvertedColumnStore(InvertedColumnStore.Kind.I32,
                                              InvertedColumnStore.Kind.BOOL);
             var b = new InvertedColumnStore(InvertedColumnStore.Kind.I32,
                                              InvertedColumnStore.Kind.BOOL)) {
            var handle = a.push(9, true);
            var foreign = b.push(9, true);
            assertThrows(IllegalArgumentException.class, () -> a.get(foreign, 0));
            assertThrows(IllegalArgumentException.class, () -> a.push(2L, false));
            assertThrows(IllegalArgumentException.class, () -> a.push(2));
            assertThrows(IllegalArgumentException.class, () -> a.push(2, null));
            assertThrows(IllegalArgumentException.class, () -> a.updateRow(handle, 4, "bad"));
            assertArrayEquals(new Object[]{9, true}, a.snapshot(handle));
            assertEquals(1, a.size());
            assertThrows(IllegalArgumentException.class, () -> a.reserve(-1));
            assertThrows(ArithmeticException.class, () -> a.reserve(Integer.MAX_VALUE));
            assertEquals(1, a.size());
        }
    }

    @Test
    void fieldBorrowMatrixAndTeardownRevocation() {
        var store = new InvertedColumnStore(InvertedColumnStore.Kind.I32,
                                           InvertedColumnStore.Kind.F64);
        var row = store.push(1, 2.0);
        var read1 = store.borrowRead(0);
        var read2 = store.borrowRead(0);
        var write = store.borrowWrite(1);
        assertEquals(1, read1.get(0));
        write.set(0, 8.0);
        assertThrows(IllegalStateException.class, () -> store.set(row, 0, 9));
        assertThrows(IllegalStateException.class, () -> store.get(row, 1));
        assertThrows(IllegalStateException.class, () -> store.reserve(20));
        assertThrows(IllegalStateException.class, () -> store.eraseSwap(row));
        assertThrows(IllegalStateException.class, () -> store.borrowWrite(0));
        assertThrows(IllegalStateException.class, () -> store.borrowRead(1));
        assertThrows(IllegalStateException.class, () -> read1.set(0, 9));
        write.close();
        read1.close();
        read2.close();
        assertEquals(8.0, store.get(row, 1));
        try (var exclusive = store.borrowWrite(0)) { exclusive.set(0, 12); }
        assertEquals(12, store.get(row, 0));
        var loan = store.borrowRead(0);
        store.close();
        assertThrows(IllegalStateException.class, () -> loan.get(0));
        assertThrows(IllegalStateException.class, () -> store.get(row, 0));
        loan.close();
        loan.close();
    }

    @Test
    void disjointWritersAndWholeRowSnapshotsAreCoherent() throws InterruptedException {
        try (var store = new InvertedColumnStore(InvertedColumnStore.Kind.I32,
                                                 InvertedColumnStore.Kind.I64)) {
            var h = store.push(0, 0L);
            AtomicBoolean broken = new AtomicBoolean(false);
            Thread writer = new Thread(() -> {
                for (int i = 0; i < 2000; i++) store.updateRow(h, i, -(long) i);
            });
            Thread reader = new Thread(() -> {
                for (int i = 0; i < 2000; i++) {
                    Object[] row = store.snapshot(h);
                    if ((long) (int) row[0] != -(long) row[1]) broken.set(true);
                }
            });
            writer.start(); reader.start();
            writer.join(); reader.join();
            assertFalse(broken.get());
            Thread x = new Thread(() -> {
                try (var loan = store.borrowWrite(0)) {
                    for (int i = 0; i < 500; i++) loan.set(0, i);
                }
            });
            Thread y = new Thread(() -> {
                try (var loan = store.borrowWrite(1)) {
                    for (int i = 0; i < 500; i++) loan.set(0, (long) i);
                }
            });
            x.start(); y.start(); x.join(); y.join();
            assertEquals(499, store.get(h, 0));
            assertEquals(499L, store.get(h, 1));
        }
    }

    @Test
    void handlesAreStoreIssuedCapabilitiesNotForgeableNumericCoordinates() throws Exception {
        try (var store = new InvertedColumnStore(InvertedColumnStore.Kind.I32);
             var foreign = new InvertedColumnStore(InvertedColumnStore.Kind.I32)) {
            var valid = store.push(17);
            var foreignHandle = foreign.push(17);
            assertThrows(IllegalArgumentException.class, () -> store.get(foreignHandle, 0));

            var constructor = java.util.Arrays.stream(InvertedColumnStore.Handle.class
                            .getDeclaredConstructors())
                    .filter(candidate -> candidate.getParameterCount() == 4)
                    .findFirst().orElseThrow();
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()),
                    "guest code must not be able to mint a handle from owner/slot/generation");
            constructor.setAccessible(true);
            var forged = (InvertedColumnStore.Handle) constructor.newInstance(
                    new Object(), valid.owner(), valid.slot(), valid.generation());
            assertThrows(IllegalArgumentException.class, () -> store.get(forged, 0));
            assertEquals(17, store.get(valid, 0));
        }
    }

    @Test
    void deletingRowsScrubsVacatedTailAndClosingClearsRemainingBackingBytes() throws Exception {
        var store = new InvertedColumnStore(
                InvertedColumnStore.Kind.I64, InvertedColumnStore.Kind.BOOL);
        var first = store.push(0x123456789ABCDEFL, true);
        var second = store.push(0x2FEDCBA98765432L, false);
        var columnsField = InvertedColumnStore.class.getDeclaredField("columns");
        columnsField.setAccessible(true);
        byte[][] backing = (byte[][]) columnsField.get(store);
        try {
            store.eraseSwap(first);
            assertArrayEquals(new Object[]{0x2FEDCBA98765432L, false}, store.snapshot(second));
            assertThrows(IllegalArgumentException.class, () -> store.get(first, 0));
            // Row 1 was vacated by swap-delete and must no longer contain the
            // bytes of either deleted or moved data.
            for (int column = 0; column < backing.length; column++) {
                int stride = column == 0 ? Long.BYTES : 1;
                for (int offset = stride; offset < 2 * stride; offset++) {
                    assertEquals((byte) 0, backing[column][offset]);
                }
            }
        } finally {
            store.close();
        }
        for (byte[] bytes : backing) {
            for (byte value : bytes) assertEquals((byte) 0, value);
        }
        assertThrows(IllegalStateException.class, () -> store.get(second, 0));
    }

    @Test
    void malformedRowTransformsNeverPartiallyCommit() {
        try (var store = new InvertedColumnStore(
                InvertedColumnStore.Kind.I32, InvertedColumnStore.Kind.BOOL)) {
            var handle = store.push(7, true);
            Object[] before = store.snapshot(handle);
            assertThrows(IllegalStateException.class, () ->
                    store.transformRow(handle, row -> new Object[]{12}));
            assertArrayEquals(before, store.snapshot(handle));
            assertThrows(IllegalArgumentException.class, () ->
                    store.transformRow(handle, row -> new Object[]{12, "not bool"}));
            assertArrayEquals(before, store.snapshot(handle));
            assertThrows(IllegalStateException.class, () ->
                    store.transformRow(handle, row -> null));
            assertArrayEquals(before, store.snapshot(handle));
            assertThrows(ArithmeticException.class, () ->
                    store.transformRow(handle, row -> {
                        row[0] = 99;
                        throw new ArithmeticException("abort before commit");
                    }));
            assertArrayEquals(before, store.snapshot(handle));
            store.transformRow(handle, row -> {
                row[0] = 8;
                row[1] = false;
                return row;
            });
            assertArrayEquals(new Object[]{8, false}, store.snapshot(handle));
        }
    }

    @Test
    void reentrantStoreOperationsTaintAndAbortWholeRowTransaction() {
        try (var store = new InvertedColumnStore(
                InvertedColumnStore.Kind.I32, InvertedColumnStore.Kind.I64)) {
            var h = store.push(7, 9L);
            var other = store.push(10, 11L);
            Object[] before = store.snapshot(h);
            // Even catching the rejected nested operation is not permission
            // to publish a row computed from an invalid transaction.
            assertThrows(IllegalStateException.class, () ->
                    store.transformRow(h, row -> {
                        row[0] = 25;
                        assertThrows(IllegalStateException.class, () -> store.set(other, 0, 200));
                        return row;
                    }));
            assertArrayEquals(before, store.snapshot(h));
            assertArrayEquals(new Object[]{10, 11L}, store.snapshot(other));
            assertThrows(IllegalStateException.class, () ->
                    store.transformRow(h, row -> {
                        row[1] = 99L;
                        assertThrows(IllegalStateException.class, store::size);
                        return row;
                    }));
            assertArrayEquals(before, store.snapshot(h));
            assertThrows(IllegalStateException.class, () ->
                    store.transformRow(h, row -> {
                        assertThrows(IllegalStateException.class, () -> store.reserve(10));
                        return row;
                    }));
            assertEquals(2, store.size());
            assertThrows(IllegalStateException.class, () ->
                    store.transformRow(h, row -> {
                        assertThrows(IllegalStateException.class, () ->
                                store.transformRow(other, nested -> nested));
                        return row;
                    }));
            assertArrayEquals(before, store.snapshot(h));
            // A tainted attempt must not poison the *next* valid transaction.
            store.transformRow(h, row -> {
                row[0] = 12;
                return row;
            });
            assertArrayEquals(new Object[]{12, 9L}, store.snapshot(h));
        }
    }

    @Test
    void reentrantTeardownIsRejectedAndNeverCommitsPartialRow() {
        var store = new InvertedColumnStore(InvertedColumnStore.Kind.I32);
        var h = store.push(7);
        assertThrows(IllegalStateException.class, () ->
                store.transformRow(h, row -> {
                    row[0] = 999;
                    store.close();
                    return row;
                }));
        // Teardown is not allowed to run inside an atomic method callback;
        // the failed update leaves the store intact and independently closable.
        assertEquals(7, store.get(h, 0));
        store.close();
        assertThrows(IllegalStateException.class, () -> store.get(h, 0));
        assertThrows(IllegalStateException.class, () -> store.push(1));
    }

    @Test
    void transactionCannotCloseOrReenterAndRollbackWorks() {
        try (var store = new InvertedColumnStore(InvertedColumnStore.Kind.I32)) {
            var row = store.push(10);
            assertThrows(IllegalStateException.class, () ->
                    store.transformRow(row, values -> {
                        assertThrows(IllegalStateException.class, store::close);
                        assertThrows(IllegalStateException.class, () -> store.get(row, 0));
                        assertThrows(IllegalStateException.class, () -> store.set(row, 0, 21));
                        values[0] = 20;
                        return values;
                    }));
            // Even swallowed nested-access exceptions taint the transaction.
            assertEquals(10, store.get(row, 0));
            store.transformRow(row, values -> {
                values[0] = 20;
                return values;
            });
            assertEquals(20, store.get(row, 0));
            assertThrows(IllegalArgumentException.class, () ->
                    store.transformRow(row, values -> new Object[]{"wrong type"}));
            assertEquals(20, store.get(row, 0));
            assertThrows(IllegalStateException.class, () ->
                    store.transformRow(row, values -> {
                        values[0] = 99;
                        throw new IllegalStateException("aborted");
                    }));
            assertEquals(20, store.get(row, 0));
            store.set(row, 0, 30);
            assertEquals(30, store.get(row, 0));
        }
    }

    @Test
    void deterministicHandleStressPreservesRows() {
        try (var store = new InvertedColumnStore(InvertedColumnStore.Kind.I32,
                                                 InvertedColumnStore.Kind.BOOL)) {
            record Expected(InvertedColumnStore.Handle handle, int value, boolean flag) {}
            List<Expected> expected = new ArrayList<>();
            Random random = new Random(2937741);
            for (int step = 0; step < 1500; step++) {
                if (expected.isEmpty() || random.nextInt(4) != 0) {
                    expected.add(new Expected(store.push(step, step % 2 == 0), step, step % 2 == 0));
                } else {
                    Expected deleted = expected.remove(random.nextInt(expected.size()));
                    store.eraseSwap(deleted.handle());
                    assertThrows(IllegalArgumentException.class, () -> store.get(deleted.handle(), 0));
                }
                assertEquals(expected.size(), store.size());
                for (Expected row : expected) {
                    assertArrayEquals(new Object[]{row.value(), row.flag()}, store.snapshot(row.handle()));
                }
            }
        }
    }
    @Test
    void floatingColumnsPreserveSignedZeroAndRejectNaNOnlyWhenABISpecRequiresIt() {
        // Storage must not silently convert -0.0 into +0.0. This is a
        // correctness prerequisite for any future compiler SIMD lowering.
        try (var store = new InvertedColumnStore(
                InvertedColumnStore.Kind.F32, InvertedColumnStore.Kind.F64)) {
            var row = store.push(-0.0f, -0.0d);
            assertEquals(Float.floatToRawIntBits(-0.0f),
                    Float.floatToRawIntBits((Float) store.get(row, 0)));
            assertEquals(Double.doubleToRawLongBits(-0.0d),
                    Double.doubleToRawLongBits((Double) store.get(row, 1)));
            store.updateRow(row, Float.NaN, Double.NaN);
            assertTrue(Float.isNaN((Float) store.get(row, 0)));
            assertTrue(Double.isNaN((Double) store.get(row, 1)));
            try (var write = store.borrowWrite(0)) {
                write.set(0, -0.0f);
            }
            assertEquals(Float.floatToRawIntBits(-0.0f),
                    Float.floatToRawIntBits((Float) store.get(row, 0)));
            store.eraseSwap(row);
            assertThrows(IllegalArgumentException.class, () -> store.get(row, 1));
        }
    }


    @Test
    void canonicalDopV1DenseSwapReuseKeepsOpaqueGenerationalHandlesDistinct() {
        // Host-only reference for contracts/dop-inverted-v1; this JUnit test
        // does NOT compile/execute either canonical .atto guest fixture.
        try (var store = new InvertedColumnStore(
                InvertedColumnStore.Kind.I64, InvertedColumnStore.Kind.I64);
             var foreign = new InvertedColumnStore(
                InvertedColumnStore.Kind.I64, InvertedColumnStore.Kind.I64)) {
            var removed = store.push(7L, 70L);
            var survivor = store.push(19L, 190L);
            var foreignHandle = foreign.push(19L, 190L);
            store.eraseSwap(removed);

            assertEquals(1, store.size());
            assertEquals(19L, store.get(survivor, 0));
            assertEquals(190L, store.get(survivor, 1));
            var moved = store.handleAt(0);
            assertEquals(survivor.slot(), moved.slot());
            assertEquals(survivor.generation(), moved.generation());
            assertThrows(IllegalArgumentException.class, () -> store.handleAt(1),
                    "a former dense index beyond live length is not valid");
            assertThrows(IllegalArgumentException.class, () -> store.get(removed, 0),
                    "deletion revokes the old issued row capability");
            assertThrows(IllegalArgumentException.class, () -> store.get(foreignHandle, 0),
                    "owner identity may not be borrowed across stores");

            var reused = store.push(42L, 420L);
            assertEquals(2, store.size());
            assertEquals(42L, store.get(store.handleAt(1), 0));
            assertEquals(420L, store.get(reused, 1));
            assertEquals(19L, store.get(survivor, 0));
            assertEquals(removed.slot(), reused.slot(),
                    "recycling the free identity slot is permitted");
            assertNotEquals(removed.generation(), reused.generation(),
                    "recycled identity must advance its generation");
            assertThrows(IllegalArgumentException.class,
                    () -> store.set(removed, 0, 123L),
                    "an old handle cannot mutate a newly reused slot");
        }
    }

    @Test
    void rawRecordColumnsEnforceTheSharedHostReferenceStrideCap() {
        assertDoesNotThrow(() -> InvertedColumnStore.ColumnSpec.record(64 * 1024));
        assertThrows(IllegalArgumentException.class,
                () -> InvertedColumnStore.ColumnSpec.record(64 * 1024 + 1));
        assertThrows(IllegalArgumentException.class,
                () -> new InvertedColumnStore.ColumnSpec(
                        InvertedColumnStore.Kind.RECORD, Integer.MAX_VALUE));
    }


    @Test
    void successfulSoAResizeScrubsRetiredPayloadAndIdentityWithoutLosingRows()
            throws ReflectiveOperationException {
        // Test-private reflection inspects *retired* backing; no backing
        // storage reference is granted to an AttoLang guest.
        var columnsField = InvertedColumnStore.class.getDeclaredField("columns");
        columnsField.setAccessible(true);
        var generationsField = InvertedColumnStore.class.getDeclaredField("generations");
        generationsField.setAccessible(true);
        var rowSlotsField = InvertedColumnStore.class.getDeclaredField("rowSlots");
        rowSlotsField.setAccessible(true);
        byte[] record = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        try (var store = new InvertedColumnStore(
                InvertedColumnStore.ColumnSpec.scalar(InvertedColumnStore.Kind.I32),
                InvertedColumnStore.ColumnSpec.record(record.length),
                InvertedColumnStore.ColumnSpec.scalar(InvertedColumnStore.Kind.I64))) {
            var row = store.push(0x12345678, record, 987654321L);
            byte[][] retired = (byte[][]) columnsField.get(store);
            long[] oldGenerations = (long[]) generationsField.get(store);
            int[] oldRowSlots = (int[]) rowSlotsField.get(store);
            assertTrue(java.util.Arrays.stream(retired).anyMatch(bytes ->
                    java.util.Arrays.mismatch(bytes, new byte[bytes.length]) != -1));
            store.reserve(8);
            assertNotSame(retired, columnsField.get(store));
            for (byte[] bytes : retired) {
                assertArrayEquals(new byte[bytes.length], bytes,
                        "retired SoA backing must be scrubbed after growth");
            }
            assertArrayEquals(new long[oldGenerations.length], oldGenerations);
            assertArrayEquals(new int[oldRowSlots.length], oldRowSlots);
            Object[] observed = store.snapshot(row);
            assertEquals(0x12345678, observed[0]);
            assertArrayEquals(record, (byte[]) observed[1]);
            assertEquals(987654321L, observed[2]);
            store.push(77, new byte[8], 9L);
            assertEquals(2, store.size());
        }
    }

    @Test
    void implicitGrowthScrubsRetiredColumnsButOverflowLeavesCurrentBacking()
            throws ReflectiveOperationException {
        var columnField = InvertedColumnStore.class.getDeclaredField("columns");
        columnField.setAccessible(true);
        try (var store = new InvertedColumnStore(
                InvertedColumnStore.Kind.I64, InvertedColumnStore.Kind.BOOL)) {
            var first = store.push(0x1020304050607080L, true);
            byte[][] old = (byte[][]) columnField.get(store);
            store.push(123L, false);
            assertNotSame(old, columnField.get(store));
            for (byte[] bytes : old) {
                assertArrayEquals(new byte[bytes.length], bytes);
            }
            byte[][] current = (byte[][]) columnField.get(store);
            assertThrows(ArithmeticException.class,
                    () -> store.reserve(Integer.MAX_VALUE));
            assertSame(current, columnField.get(store),
                    "failed capacity growth must preserve the live bytes");
            assertEquals(0x1020304050607080L, store.get(first, 0));
            assertEquals(true, store.get(first, 1));
            assertEquals(2, store.size());
        }
    }

@Test
    void structuralMutationDoesNotProceedWithAnyLiveReadLoan() {
        try (var store = new InvertedColumnStore(
                InvertedColumnStore.Kind.I32, InvertedColumnStore.Kind.I32)) {
            var old = store.push(1, 2);
            var loan = store.borrowRead(0);
            try {
                assertThrows(IllegalStateException.class, () -> store.reserve(32));
                assertThrows(IllegalStateException.class, () -> store.eraseSwap(old));
                assertThrows(IllegalStateException.class, () -> store.push(3, 4));
                assertArrayEquals(new Object[] {1, 2}, store.snapshot(old));
            } finally {
                loan.close();
            }
            store.eraseSwap(old);
            assertThrows(IllegalArgumentException.class, () -> store.get(old, 0));
            assertEquals(0, store.size());
        }
    }


    @Test
    void guardedDenseAccessUsesSameLockForIdentityEpochBoundsAndFieldLoans() {
        var store = new InvertedColumnStore(InvertedColumnStore.Kind.I32,
                                            InvertedColumnStore.Kind.BOOL);
        try (store;
             var other = new InvertedColumnStore(InvertedColumnStore.Kind.I32,
                                                  InvertedColumnStore.Kind.BOOL)) {
            var first = store.push(42, true);
            var initial = store.captureLayoutGuard();
            assertEquals(42, store.readIndexGuarded(initial, 0, 0));
            store.writeIndexGuarded(initial, 0, 0, 43);
            assertEquals(43, store.get(first, 0));

            other.push(7, false);
            var foreign = other.captureLayoutGuard();
            assertThrows(IllegalStateException.class,
                    () -> store.readIndexGuarded(foreign, 0, 0));
            assertThrows(IllegalStateException.class,
                    () -> store.writeIndexGuarded(foreign, 0, 0, 999));
            assertThrows(IllegalStateException.class,
                    () -> store.readIndexGuarded(null, 0, 0));

            try (var read = store.borrowRead(0)) {
                assertEquals(43, store.readIndexGuarded(initial, 0, 0));
                assertThrows(IllegalStateException.class,
                        () -> store.writeIndexGuarded(initial, 0, 0, 999));
            }
            try (var write = store.borrowWrite(1)) {
                assertThrows(IllegalStateException.class,
                        () -> store.readIndexGuarded(initial, 0, 1));
                assertThrows(IllegalStateException.class,
                        () -> store.writeIndexGuarded(initial, 0, 1, false));
            }
            assertEquals(43, store.get(first, 0));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> store.readIndexGuarded(initial, -1, 0));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> store.writeIndexGuarded(initial, 1, 0, 1));

            store.reserve(8);
            assertThrows(IllegalStateException.class,
                    () -> store.readIndexGuarded(initial, 0, 0));
            assertThrows(IllegalStateException.class,
                    () -> store.writeIndexGuarded(initial, 0, 0, 888));
            var afterReserve = store.captureLayoutGuard();
            assertTrue(afterReserve.epoch() > initial.epoch());
            store.reserve(8); // Java host no-op reserve preserves physical layout
            store.set(first, 0, 44); // ordinary value write does not move columns
            assertEquals(44, store.readIndexGuarded(afterReserve, 0, 0));

            // Failure must not alter row values; the guard fails closed even
            // if an allocator changes capacity before a later failure.
            assertThrows(ArithmeticException.class,
                    () -> store.reserve(Integer.MAX_VALUE));
            assertThrows(IllegalStateException.class,
                    () -> store.readIndexGuarded(afterReserve, 0, 0));
            assertEquals(44, store.get(first, 0));

            var beforePush = store.captureLayoutGuard();
            var second = store.push(99, false);
            assertThrows(IllegalStateException.class,
                    () -> store.readIndexGuarded(beforePush, 0, 0));
            var beforeErase = store.captureLayoutGuard();
            store.eraseSwap(first);
            assertThrows(IllegalStateException.class,
                    () -> store.readIndexGuarded(beforeErase, 0, 0));
            assertEquals(99, store.readIndexGuarded(store.captureLayoutGuard(), 0, 0));
            assertEquals(99, store.get(second, 0));
            assertThrows(IllegalArgumentException.class, () -> store.get(first, 0));
        }
        assertThrows(IllegalStateException.class, store::captureLayoutGuard);
    }
}
