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
}
