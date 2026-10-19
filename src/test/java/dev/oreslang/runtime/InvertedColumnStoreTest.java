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
