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
    void teardownInsideRowTransformRevokesStoreAndCannotCommit() {
        var store = new InvertedColumnStore(InvertedColumnStore.Kind.I32);
        var h = store.push(7);
        assertThrows(IllegalStateException.class, () ->
                store.transformRow(h, row -> {
                    row[0] = 999;
                    store.close();
                    return row;
                }));
        assertThrows(IllegalStateException.class, () -> store.get(h, 0));
        assertThrows(IllegalStateException.class, () -> store.push(1));
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
}
