package dev.oreslang;

import dev.oreslang.runtime.AtomicI64Cell;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Host primitive tests; these do NOT establish AttoLang guest lowering. */
final class AtomicI64CellTest {
    @Test
    void basicOperationsAndCompareExchange() {
        AtomicI64Cell cell = new AtomicI64Cell(5);
        assertEquals(5L, cell.load());
        assertEquals(5L, cell.swap(7));
        assertEquals(7L, cell.load());
        assertFalse(cell.compare_exchange(5, 9).exchanged());
        assertEquals(7L, cell.compare_exchange(5, 9).observed());
        assertTrue(cell.compare_exchange(7, 11).exchanged());
        assertEquals(11L, cell.load());
        cell.store(3);
        assertEquals(3L, cell.fetch_and(2));
        assertEquals(2L, cell.fetch_or(4));
        assertEquals(6L, cell.fetch_xor(7));
        assertEquals(1L, cell.load());
    }

    @Test
    void checkedArithmeticDoesNotPublishOverflow() {
        AtomicI64Cell max = new AtomicI64Cell(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> max.fetch_add(1));
        assertEquals(Long.MAX_VALUE, max.load());
        AtomicI64Cell min = new AtomicI64Cell(Long.MIN_VALUE);
        assertThrows(ArithmeticException.class, () -> min.fetch_sub(1));
        assertEquals(Long.MIN_VALUE, min.load());
        assertEquals(1L, new AtomicI64Cell(1).fetch_add(2));
        assertEquals(2L, new AtomicI64Cell(2).fetch_sub(1));
    }

    @Test
    void concurrentIncrementsDoNotLoseUpdates() throws Exception {
        int workers = 8;
        int each = 10_000;
        AtomicI64Cell counter = new AtomicI64Cell(0);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int j = 0; j < each; j++) counter.fetch_add(1);
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) future.get();
        }
        assertEquals((long) workers * each, counter.load());
    }

    @Test
    void publicationIsVisibleAfterFlagStore() throws Exception {
        AtomicI64Cell ready = new AtomicI64Cell(0);
        int[] ordinaryPayload = new int[1];
        Thread producer = new Thread(() -> {
            ordinaryPayload[0] = 42;
            ready.store(1);
        });
        producer.start();
        while (ready.load() == 0) Thread.onSpinWait();
        assertEquals(42, ordinaryPayload[0]);
        producer.join();
    }

    @Test
    void orderedLoadsAndStoresRejectIllegalModesBeforeMutation() {
        AtomicI64Cell cell = new AtomicI64Cell(10);
        assertEquals(10L, cell.load(0)); // opaque: at least relaxed
        assertEquals(10L, cell.load(1)); // acquire
        assertEquals(10L, cell.load(4)); // seq_cst
        for (int order : new int[] {-1, 2, 3, 5, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> cell.load(order));
        }
        for (int order : new int[] {-1, 1, 3, 5, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> cell.store(99, order));
            assertEquals(10L, cell.load(), "invalid store must never publish");
        }
        cell.store(11L, 0);
        assertEquals(11L, cell.load(0));
        cell.store(12L, 2);
        assertEquals(12L, cell.load(1));
        cell.store(13L, 4);
        assertEquals(13L, cell.load(4));
    }

    @Test
    void orderedRmwAndCompareExchangePreserveObservedValues() {
        AtomicI64Cell cell = new AtomicI64Cell(10);
        assertEquals(10L, cell.swap(20, 0));
        assertEquals(20L, cell.fetch_add(2, 1));
        assertEquals(22L, cell.fetch_sub(2, 2));
        assertEquals(20L, cell.fetch_or(3, 3));
        assertEquals(23L, cell.fetch_and(22, 4));
        assertEquals(22L, cell.fetch_xor(6, 0));
        assertEquals(16L, cell.load());
        assertFalse(cell.compare_exchange(10, 99, 4, 1).exchanged());
        var success = cell.compare_exchange(16, 42, 3, 1);
        assertTrue(success.exchanged());
        assertEquals(16L, success.observed());
        assertEquals(42L, cell.load());
        for (int order : new int[] {-1, 5, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> cell.fetch_add(100, order));
            assertThrows(IllegalArgumentException.class, () -> cell.swap(100, order));
            assertEquals(42L, cell.load(), "invalid RMW must never mutate");
        }
    }

    @Test
    void invalidCompareExchangeFailureOrdersNeverChangeCell() {
        AtomicI64Cell cell = new AtomicI64Cell(42);
        for (int[] orders : new int[][] {
                {0, 1}, {0, 4}, {1, 4}, {2, 1}, {2, 2},
                {3, 4}, {4, 2}, {4, 3}, {5, 0}, {-1, 0},
                {4, -1}, {4, Integer.MAX_VALUE}
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> cell.compare_exchange(42, 0, orders[0], orders[1]));
            assertEquals(42L, cell.load(), "invalid CAS must never publish");
        }
        assertTrue(cell.compare_exchange(42, 41, 0, 0).exchanged());
        assertEquals(41L, cell.load());
        assertTrue(cell.compare_exchange(41, 42, 4, 4).exchanged());
    }

    @Test
    void orderedArithmeticOverflowNeverPublishesWrappedValue() {
        AtomicI64Cell max = new AtomicI64Cell(Long.MAX_VALUE);
        for (int order = 0; order <= 4; order++) {
            int requested = order;
            assertThrows(ArithmeticException.class, () -> max.fetch_add(1, requested));
            assertEquals(Long.MAX_VALUE, max.load());
        }
        AtomicI64Cell min = new AtomicI64Cell(Long.MIN_VALUE);
        for (int order = 0; order <= 4; order++) {
            int requested = order;
            assertThrows(ArithmeticException.class, () -> min.fetch_sub(1, requested));
            assertEquals(Long.MIN_VALUE, min.load());
        }
    }

    @Test
    void releaseAcquirePublishesOrdinaryPayload() throws Exception {
        AtomicI64Cell flag = new AtomicI64Cell(0);
        int[] payload = {0};
        Thread writer = new Thread(() -> {
            payload[0] = 42;
            flag.store(1, 2); // release
        });
        writer.start();
        // Bound the test to prevent a stuck VM from hanging the whole suite.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (flag.load(1) != 1 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        try {
            assertEquals(1L, flag.load(1), "release/acquire flag not observed");
            assertEquals(42, payload[0]);
        } finally {
            writer.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
        }
        assertFalse(writer.isAlive());
    }

    @Test
    void contendedOrderedRmwNeverLosesUpdates() throws Exception {
        final int workers = 4, each = 5_000;
        AtomicI64Cell counter = new AtomicI64Cell(0);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                final int order = i; // relaxed/acquire/release/acq_rel
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    for (int j = 0; j < each; j++) counter.fetch_add(1, order);
                    return null;
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> future : futures)
                future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals((long) workers * each, counter.load(4));
    }

}
