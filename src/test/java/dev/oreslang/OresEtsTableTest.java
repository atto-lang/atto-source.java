package dev.oreslang;

import dev.oreslang.runtime.OresEtsTable;
import dev.oreslang.runtime.OresSymbol;
import java.math.BigInteger;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class OresEtsTableTest {
    @Test
    void immutableValuesAndAtomicOperations() {
        try (var table = new OresEtsTable<String, Long>(10)) {
            assertTrue(table.insertNew("k", 1L));
            assertFalse(table.insertNew("k", 2L));
            assertEquals(Long.valueOf(1L), table.lookup("k").orElseThrow());
            assertFalse(table.compareExchange("k", 2L, 3L));
            assertTrue(table.compareExchange("k", 1L, 3L));
            assertEquals(Long.valueOf(3L), table.take("k").orElseThrow());
            assertTrue(table.lookup("k").isEmpty());
            assertEquals(0, table.size());
        }
    }

    @Test
    void immutableAdmissionAndClose() {
        try (var table = new OresEtsTable<Object, Object>(1)) {
            assertThrows(IllegalArgumentException.class, () -> table.insert("key", new int[]{1}));
            assertThrows(NullPointerException.class, () -> table.insert(null, "value"));
            table.insert("a", "b");
            assertThrows(IllegalStateException.class, () -> table.insert("c", "d"));
            assertEquals("b", table.snapshot().get("a"));
        }
        var table = new OresEtsTable<String, Integer>(1);
        table.close();
        table.close();
        assertTrue(table.isClosed());
        assertThrows(IllegalStateException.class, () -> table.lookup("x"));
    }

    @Test
    void competingWritersObserveOneInsertNewWinner() throws Exception {
        final int workers = 10;
        try (var table = new OresEtsTable<String, Integer>(20)) {
            var start = new CountDownLatch(1);
            var finished = new CountDownLatch(workers);
            var successes = new AtomicInteger();
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < workers; i++) {
                int index = i;
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        if (table.insertNew("winner", index)) successes.incrementAndGet();
                    } catch (Throwable e) { failures.add(e); }
                    finally { finished.countDown(); }
                }));
            }
            start.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            for (var t : threads) t.join();
            assertTrue(failures.isEmpty(), failures.toString());
            assertEquals(1, successes.get());
            assertEquals(1, table.size());
        }
    }

    @Test
    void quotaCannotBeOversubscribedByCompetingKeys() throws Exception {
        try (var table = new OresEtsTable<Integer, Integer>(1)) {
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(10);
            var winners = new AtomicInteger();
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            for (int i = 0; i < 10; i++) {
                int key = i;
                Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        table.insertNew(key, key);
                        winners.incrementAndGet();
                    } catch (IllegalStateException full) {
                        // Expected for every key after quota is reserved.
                    } catch (Throwable e) { failures.add(e); }
                    finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertTrue(failures.isEmpty(), failures.toString());
            assertEquals(1, winners.get());
            assertEquals(1, table.size());
        }
    }

    @Test
    void concurrentCompareExchangeIncrementsWithoutLosingUpdates() throws Exception {
        final int workers = 10, perWorker = 1000;
        try (var table = new OresEtsTable<String, Long>(2)) {
            table.insert("counter", 0L);
            var threads = new ArrayList<Thread>();
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            for (int i = 0; i < workers; i++) {
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        for (int j = 0; j < perWorker; j++) {
                            for (;;) {
                                long old = table.lookup("counter").orElseThrow();
                                if (table.compareExchange("counter", old, old + 1)) break;
                            }
                        }
                    } catch (Throwable e) { failures.add(e); }
                }));
            }
            for (var t : threads) t.join();
            assertTrue(failures.isEmpty(), failures.toString());
            assertEquals(Long.valueOf(10_000L), table.lookup("counter").orElseThrow());
        }
    }

    @Test
    void symbolsUseNominalLogicalKeysWithoutInterning() {
        try (var table = new OresEtsTable<Object, String>(5, 1024)) {
            var first = OresSymbol.process("caf\u00e9");
            var normalized = OresSymbol.process("cafe\u0301");
            assertNotSame(first, normalized);
            assertEquals(first, normalized);
            assertTrue(table.insertNew(first, "symbol"));
            assertFalse(table.insertNew(normalized, "duplicate"));
            assertTrue(table.insertNew("caf\u00e9", "string"));
            assertTrue(table.insertNew(OresSymbol.stable("00000000-0000-0000-0000-000000000001"), "stable"));
            assertEquals("symbol", table.lookup(normalized).orElseThrow());
            assertEquals("string", table.lookup("caf\u00e9").orElseThrow());
            assertEquals(3, table.size());
        }
    }

    @Test
    void byteQuotaIsEnforcedForInsertsAndReplacementsAndRefunds() {
        try (var table = new OresEtsTable<String, String>(3, 8)) {
            assertTrue(table.insertNew("a", "aa")); // 3 bytes
            assertTrue(table.insertNew("b", "bb")); // 3 bytes
            assertEquals(6L, table.byteSize());
            assertThrows(IllegalStateException.class, () -> table.insertNew("c", "cccc"));
            assertThrows(IllegalStateException.class, () -> table.insert("a", "longer"));
            assertThrows(IllegalStateException.class, () -> table.compareExchange("a", "aa", "longer"));
            assertEquals(6L, table.byteSize());
            assertEquals("aa", table.lookup("a").orElseThrow());
            assertTrue(table.compareExchange("a", "aa", "b")); // refund 1
            assertEquals(5L, table.byteSize());
            assertEquals("bb", table.take("b").orElseThrow()); // refund 3
            assertEquals(2L, table.byteSize());
            assertTrue(table.insertNew("c", "1234")); // reserve 5
            assertEquals(7L, table.byteSize());
            assertEquals(2, table.size());
        }
    }

    @Test
    void failedReservationsAndOversizedScalarsNeverLeakQuota() {
        try (var table = new OresEtsTable<Object, Object>(1, 2)) {
            assertThrows(IllegalStateException.class, () -> table.insertNew("a", "zzzz"));
            assertEquals(0, table.size());
            assertEquals(0L, table.byteSize());
            assertTrue(table.insertNew("a", "b"));
            assertThrows(IllegalStateException.class, () -> table.insert("a", "too big"));
            assertEquals(1, table.size());
            assertEquals(2L, table.byteSize());
            assertThrows(IllegalArgumentException.class, () -> table.insert("x".repeat(4097), "x"));
            assertThrows(IllegalArgumentException.class, () -> table.insert("\uD800", "x"));
            assertThrows(IllegalArgumentException.class, () -> table.insert(BigInteger.ONE.shiftLeft(4097), "x"));
            assertThrows(IllegalArgumentException.class,
                    () -> table.insert(new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE), "x"));
            assertEquals("b", table.lookup("a").orElseThrow());
        }
    }

    @Test
    void concurrentDistinctKeysCannotOversubscribeByteQuota() throws Exception {
        final int workers = 10;
        try (var table = new OresEtsTable<Integer, Integer>(workers, 16)) {
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(workers);
            var winners = new AtomicInteger();
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            for (int i = 0; i < workers; i++) {
                int key = i;
                Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        if (table.insertNew(key, key)) winners.incrementAndGet();
                    } catch (IllegalStateException full) {
                        // Other distinct keys hit the byte budget.
                    } catch (Throwable error) { failures.add(error); }
                    finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertTrue(failures.isEmpty(), failures.toString());
            assertEquals(1, winners.get());
            assertEquals(16L, table.byteSize());
            assertEquals(1, table.size());
        }
    }
}
