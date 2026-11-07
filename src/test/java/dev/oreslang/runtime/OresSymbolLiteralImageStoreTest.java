package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class OresSymbolLiteralImageStoreTest {
    @Test void emptyLiteralImageHasNoOrdinalsAndRetainsItsGeneration() {
        try (var store = new OresSymbolLiteralImageStore()) {
            long epoch = store.publish(List.of());
            try (var pinned = store.pinExpected(epoch)) {
                assertEquals(0, pinned.size());
                assertEquals(0L, pinned.payloadBytes());
                assertEquals(-1, pinned.indexOf(OresSymbol.process("ready")));
                assertThrows(IndexOutOfBoundsException.class, () -> pinned.get(0));
            }
            assertEquals(1, store.liveGenerationCount());
        }
    }

    @Test void maximalAsciiImageHasExactlyOneMiBOfCanonicalPayload() {
        try (var store = new OresSymbolLiteralImageStore()) {
            List<OresSymbol> full = new ArrayList<>();
            for (int i = 0; i < OresSymbolLiteralImageStore.MAX_LITERALS; i++) {
                String prefix = "tag_" + i + "_";
                full.add(OresSymbol.process(prefix + "x".repeat(256 - prefix.length())));
            }
            long epoch = store.publish(full);
            try (var pin = store.pinExpected(epoch)) {
                assertEquals(OresSymbolLiteralImageStore.MAX_LITERALS, pin.size());
                assertEquals(OresSymbolLiteralImageStore.MAX_PAYLOAD_UTF8_BYTES,
                        pin.payloadBytes());
                assertEquals(full.get(full.size() - 1), pin.get(full.size() - 1));
                assertEquals(full.size() - 1, pin.indexOf(full.get(full.size() - 1)));
            }
            full.clear();
            assertEquals(1, store.liveGenerationCount());
        }
    }

    @Test void rejectedMaximalImagePlusOneLeavesPreviousImagePublished() {
        try (var store = new OresSymbolLiteralImageStore()) {
            long old = store.publish(List.of(OresSymbol.process("ready")));
            List<OresSymbol> overflowing = new ArrayList<>();
            for (int i = 0; i <= OresSymbolLiteralImageStore.MAX_LITERALS; i++) {
                overflowing.add(OresSymbol.process("item_" + i));
            }
            assertThrows(IllegalArgumentException.class, () -> store.publish(overflowing));
            try (var preserved = store.pinExpected(old)) {
                assertEquals(1, preserved.size());
                assertEquals(OresSymbol.process("ready"), preserved.get(0));
            }
            assertEquals(1, store.liveGenerationCount());
        }
    }

    @Test void stableAndProcessNamespacesStayDistinctEvenForSameUuidText() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        try (var store = new OresSymbolLiteralImageStore()) {
            store.publish(List.of(OresSymbol.stable(uuid), OresSymbol.process(uuid)));
            try (var pinned = store.pinLatest()) {
                assertEquals(2, pinned.size());
                assertEquals(0, pinned.indexOf(OresSymbol.stable(uuid)));
                assertEquals(1, pinned.indexOf(OresSymbol.process(uuid)));
                assertNotEquals(pinned.get(0), pinned.get(1));
                assertThrows(SecurityException.class,
                        () -> pinned.copyForUntrustedCoreV1(0));
                assertThrows(SecurityException.class,
                        () -> pinned.copyForUntrustedCoreV1(1));
            }
        }
    }

    @Test void concurrentWritersProduceUniqueProcessLocalEpochsWithoutLostImages()
            throws Exception {
        try (var store = new OresSymbolLiteralImageStore();
             var workers = Executors.newFixedThreadPool(8)) {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 96; i++) {
                final int ordinal = i;
                results.add(workers.submit(() -> store.publish(
                        List.of(OresSymbol.process("thread-" + ordinal)))));
            }
            java.util.Set<Long> minted = new java.util.HashSet<>();
            for (Future<Long> result : results) {
                assertTrue(minted.add(result.get(8, TimeUnit.SECONDS)),
                        "publisher epochs must never collide");
            }
            assertEquals(results.size(), minted.size());
            assertEquals(1, store.liveGenerationCount(),
                    "all unpinned previous generations must retire");
            try (var latest = store.pinLatest()) {
                assertEquals(1, latest.size());
            }
        }
    }

    @Test void trustedReadersReuseImmutableValuesWithoutIdentityBasedEquality() {
        try (var store = new OresSymbolLiteralImageStore()) {
            long generation = store.publish(List.of(OresSymbol.process("ready"),
                    OresSymbol.process("café")));
            try (var a = store.pinExpected(generation); var b = store.pinLatest()) {
                assertEquals(2, a.size());
                assertSame(a.get(0), b.get(0));
                assertEquals(0, b.indexOf(OresSymbol.process("ready")));
                assertEquals(1, a.indexOf(OresSymbol.process("cafe\u0301")));
                assertEquals(-1, a.indexOf(OresSymbol.process("unknown")));
                assertEquals(generation, a.generation());
                assertEquals("ready".length() + "café".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        a.payloadBytes());
            }
        }
    }

    @Test void untrustedCoreCopiesNamesButNeverHandlesOrPrivateSymbols() {
        try (var store = new OresSymbolLiteralImageStore()) {
            store.publish(List.of(OresSymbol.process("ready"), OresSymbol.process("private"),
                    OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000")));
            try (var lease = store.pinLatest()) {
                var copy = lease.copyForUntrustedCoreV1(0);
                assertEquals(lease.get(0), copy);
                assertNotSame(lease.get(0), copy);
                assertThrows(SecurityException.class, () -> lease.copyForUntrustedCoreV1(1));
                assertThrows(SecurityException.class, () -> lease.copyForUntrustedCoreV1(2));
            }
        }
    }

    @Test void detachedSourceAndLogicalDuplicates() {
        try (var store = new OresSymbolLiteralImageStore()) {
            var source = new ArrayList<>(List.of(OresSymbol.process("ready")));
            long first = store.publish(source);
            source.clear();
            source.add(OresSymbol.process("other"));
            try (var lease = store.pinExpected(first)) {
                assertEquals(OresSymbol.process("ready"), lease.get(0));
            }
            assertThrows(IllegalArgumentException.class, () -> store.publish(
                    List.of(OresSymbol.process("cafe\u0301"), OresSymbol.process("café"))));
            try (var lease = store.pinExpected(first)) {
                assertEquals(1, lease.size());
            }
        }
    }

    @Test void iteratedCountIsBoundedEvenWhenReportedSizeLies() {
        try (var store = new OresSymbolLiteralImageStore()) {
            long before = store.publish(List.of(OresSymbol.process("good")));
            AbstractCollection<OresSymbol> lying = new AbstractCollection<>() {
                @Override public int size() { return 0; }
                @Override public Iterator<OresSymbol> iterator() {
                    return new Iterator<>() {
                        int next;
                        @Override public boolean hasNext() {
                            return next <= OresSymbolLiteralImageStore.MAX_LITERALS;
                        }
                        @Override public OresSymbol next() {
                            return OresSymbol.process("dynamic_" + next++);
                        }
                    };
                }
            };
            assertThrows(IllegalArgumentException.class, () -> store.publish(lying));
            try (var lease = store.pinExpected(before)) {
                assertEquals(OresSymbol.process("good"), lease.get(0));
            }
        }
    }

    @Test void publishRetainsOldPinnedGenerationAndRejectsStaleOrdinal() {
        try (var store = new OresSymbolLiteralImageStore()) {
            long first = store.publish(List.of(OresSymbol.process("ready")));
            var oldLease = store.pinExpected(first);
            long second = store.publish(List.of(OresSymbol.process("failed")));
            assertEquals(OresSymbol.process("ready"), oldLease.get(0));
            assertThrows(IllegalStateException.class, () -> store.pinExpected(first));
            try (var latest = store.pinExpected(second)) {
                assertEquals(OresSymbol.process("failed"), latest.get(0));
            }
            assertEquals(2, store.liveGenerationCount());
            oldLease.close();
            oldLease.close(); // exactly one pin refund; idempotent cleanup
            assertEquals(1, store.liveGenerationCount());
            assertThrows(IllegalStateException.class, () -> oldLease.get(0));
        }
    }

    @Test void liveGenerationQuotaFailsTransactionallyAndRecoversAfterRelease() {
        try (var store = new OresSymbolLiteralImageStore()) {
            List<OresSymbolLiteralImageStore.Lease> pinned = new ArrayList<>();
            try {
                for (int i = 0; i < OresSymbolLiteralImageStore.MAX_LIVE_GENERATIONS; i++) {
                    store.publish(List.of(OresSymbol.process("gen" + i)));
                    pinned.add(store.pinLatest());
                }
                assertEquals(OresSymbolLiteralImageStore.MAX_LIVE_GENERATIONS,
                        store.liveGenerationCount());
                assertThrows(IllegalStateException.class,
                        () -> store.publish(List.of(OresSymbol.process("overflow"))));
                try (var latest = store.pinLatest()) {
                    assertEquals(OresSymbol.process("gen7"), latest.get(0));
                }
            } finally {
                for (var lease : pinned) lease.close();
            }
            assertEquals(1, store.liveGenerationCount());
            assertTrue(store.publish(List.of(OresSymbol.process("recovered"))) > 0);
        }
    }

    @Test void generationIdFromDifferentStoreCannotSelectAnUnrelatedImage() {
        try (var first = new OresSymbolLiteralImageStore();
             var second = new OresSymbolLiteralImageStore()) {
            long a = first.publish(List.of(OresSymbol.process("a")));
            long b = second.publish(List.of(OresSymbol.process("b")));
            assertNotEquals(a, b, "different stores may never reuse live VM epochs");
            assertThrows(IllegalStateException.class, () -> first.pinExpected(b));
            assertThrows(IllegalStateException.class, () -> second.pinExpected(a));
            try (var lease = second.pinExpected(b)) {
                assertEquals(OresSymbol.process("b"), lease.get(0));
            }
        }
    }

    @Test void closingStorePreservesExistingLeasesButRejectsNewAdmission() {
        var store = new OresSymbolLiteralImageStore();
        store.publish(List.of(OresSymbol.process("ready")));
        var pinned = store.pinLatest();
        store.close();
        store.close();
        assertEquals(OresSymbol.process("ready"), pinned.get(0));
        assertThrows(IllegalStateException.class, store::pinLatest);
        assertThrows(IllegalStateException.class,
                () -> store.publish(List.of(OresSymbol.process("no"))));
        pinned.close();
        assertEquals(0, store.liveGenerationCount());
    }

    @Test void hostileIteratorCannotRecursivelyPublish() {
        try (var store = new OresSymbolLiteralImageStore()) {
            long first = store.publish(List.of(OresSymbol.process("first")));
            AbstractCollection<OresSymbol> reentrant = new AbstractCollection<>() {
                @Override public int size() { return 1; }
                @Override public Iterator<OresSymbol> iterator() {
                    return new Iterator<>() {
                        boolean next;
                        @Override public boolean hasNext() { return !next; }
                        @Override public OresSymbol next() {
                            next = true;
                            assertThrows(IllegalStateException.class, () ->
                                    store.publish(List.of(OresSymbol.process("nested"))));
                            return OresSymbol.process("second");
                        }
                    };
                }
            };
            long second = store.publish(reentrant);
            assertTrue(second > first);
            try (var latest = store.pinLatest()) {
                assertEquals(OresSymbol.process("second"), latest.get(0));
                assertEquals(1, latest.size());
            }
        }
    }

    @Test void iteratorCloseDuringPublicationCannotResurrectStore() {
        var store = new OresSymbolLiteralImageStore();
        AbstractCollection<OresSymbol> closes = new AbstractCollection<>() {
            @Override public int size() { return 1; }
            @Override public Iterator<OresSymbol> iterator() {
                return new Iterator<>() {
                    boolean next;
                    @Override public boolean hasNext() { return !next; }
                    @Override public OresSymbol next() {
                        next = true;
                        store.close();
                        return OresSymbol.process("ghost");
                    }
                };
            }
        };
        assertThrows(IllegalStateException.class, () -> store.publish(closes));
        assertEquals(0, store.liveGenerationCount());
        assertThrows(IllegalStateException.class, store::pinLatest);
    }

    @Test void slowOrHostileIteratorCannotBlockPinnedReadersOrOtherPublishers() throws Exception {
        try (var store = new OresSymbolLiteralImageStore()) {
            long old = store.publish(List.of(OresSymbol.process("old")));
            var enteredIterator = new CountDownLatch(1);
            var allowFinish = new CountDownLatch(1);
            AbstractCollection<OresSymbol> slow = new AbstractCollection<>() {
                @Override public int size() { return 1; }
                @Override public Iterator<OresSymbol> iterator() {
                    return new Iterator<>() {
                        boolean consumed;
                        @Override public boolean hasNext() { return !consumed; }
                        @Override public OresSymbol next() {
                            consumed = true;
                            enteredIterator.countDown();
                            try {
                                if (!allowFinish.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("test iterator timed out");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                            return OresSymbol.process("staged");
                        }
                    };
                }
            };
            try (var workers = Executors.newFixedThreadPool(3)) {
                Future<Long> pending = workers.submit(() -> store.publish(slow));
                try {
                    assertTrue(enteredIterator.await(3, TimeUnit.SECONDS));
                    Future<Boolean> reader = workers.submit(() -> {
                        try (var pin = store.pinExpected(old)) {
                            return pin.get(0).equals(OresSymbol.process("old"));
                        }
                    });
                    assertTrue(reader.get(2, TimeUnit.SECONDS),
                            "a publisher iterator must not hold the image monitor");
                    Future<Long> otherPublisher = workers.submit(
                            () -> store.publish(List.of(OresSymbol.process("other"))));
                    assertTrue(otherPublisher.get(2, TimeUnit.SECONDS) > old,
                            "other publishers must not block on hostile iteration");
                } finally {
                    allowFinish.countDown();
                }
                assertTrue(pending.get(3, TimeUnit.SECONDS) > old);
            }
            assertEquals(1, store.liveGenerationCount());
        }
    }

    @Test void closingDuringConcurrentStagingCannotResurrectAClosedStore() throws Exception {
        var store = new OresSymbolLiteralImageStore();
        long old = store.publish(List.of(OresSymbol.process("old")));
        var pinned = store.pinExpected(old);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        AbstractCollection<OresSymbol> slow = new AbstractCollection<>() {
            @Override public int size() { return 1; }
            @Override public Iterator<OresSymbol> iterator() {
                return new Iterator<>() {
                    boolean consumed;
                    @Override public boolean hasNext() { return !consumed; }
                    @Override public OresSymbol next() {
                        consumed = true;
                        entered.countDown();
                        try {
                            if (!finish.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("iterator wait timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return OresSymbol.process("ghost");
                    }
                };
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Long> pending = executor.submit(() -> store.publish(slow));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                Future<?> closer = executor.submit(store::close);
                closer.get(2, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
            var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> pending.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, thrown.getCause());
        } finally {
            assertEquals(OresSymbol.process("old"), pinned.get(0));
            pinned.close();
            store.close();
        }
        assertEquals(0, store.liveGenerationCount());
    }

    @Test void pinnedLiteralImagesAreReadableAcrossConcurrentHostThreads() throws Exception {
        try (var store = new OresSymbolLiteralImageStore()) {
            store.publish(List.of(OresSymbol.process("ready"), OresSymbol.process("failed")));
            try (var executor = Executors.newFixedThreadPool(4)) {
                List<Callable<Boolean>> tasks = new ArrayList<>();
                for (int i = 0; i < 120; i++) {
                    tasks.add(() -> {
                        try (var lease = store.pinLatest()) {
                            return lease.get(0).equals(OresSymbol.process("ready"))
                                    && lease.get(1).equals(OresSymbol.process("failed"));
                        }
                    });
                }
                for (Future<Boolean> done : executor.invokeAll(tasks)) {
                    assertTrue(done.get());
                }
            }
            assertEquals(1, store.liveGenerationCount());
        }
    }
}
