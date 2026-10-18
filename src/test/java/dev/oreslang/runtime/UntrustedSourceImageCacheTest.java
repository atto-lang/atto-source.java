package dev.oreslang.runtime;

import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only cache invariants; does not pretend to execute a native isolate. */
final class UntrustedSourceImageCacheTest {
    @Test
    void identicalUnitAndBytesShareOnePinnedImageAcrossActorGenerations() {
        var cache = new UntrustedSourceImageCache();
        String code = "pub routine main(): void { return; }";
        Source same;
        try (var actorOne = cache.acquire("worker.ores", code);
             var actorTwo = cache.acquire("worker.ores", code)) {
            same = actorOne.source();
            assertSame(same, actorTwo.source());
            assertEquals(1, cache.liveImages());
            assertEquals(code.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    cache.pinnedBytes());
            actorOne.close();
            assertEquals(1, cache.liveImages(), "other actor keeps code pinned");
            assertThrows(IllegalStateException.class, actorOne::source);
        }
        assertEquals(0, cache.liveImages());
        assertEquals(0L, cache.pinnedBytes());
        try (var newGeneration = cache.acquire("worker.ores", code)) {
            assertNotSame(same, newGeneration.source(),
                    "after last actor retires the old image must be reclaimable");
        }
    }

    @Test
    void unitIdentityAndCodeVersionNeverAlias() {
        var cache = new UntrustedSourceImageCache();
        try (var base = cache.acquire("a.ores", "A");
             var updated = cache.acquire("a.ores", "B");
             var differentUnit = cache.acquire("b.ores", "A")) {
            assertNotSame(base.source(), updated.source());
            assertNotSame(base.source(), differentUnit.source());
            assertEquals(3, cache.liveImages());
            assertEquals("a.ores", base.source().getName());
            assertEquals("a.ores", updated.source().getName(),
                    "different versions must preserve canonical import identity");
        }
        assertEquals(0, cache.liveImages());
    }

    @Test
    void independentUntrustedIsolateCachesNeverShareSourceObjects() {
        var one = new UntrustedSourceImageCache();
        var two = new UntrustedSourceImageCache();
        try (var a = one.acquire("core.ores", "core-v1");
             var b = two.acquire("core.ores", "core-v1")) {
            assertNotSame(a.source(), b.source());
            assertEquals(1, one.liveImages());
            assertEquals(1, two.liveImages());
        }
    }

    @Test
    void capacityAndAdmissionFailuresAreAccountingNeutral() {
        var cache = new UntrustedSourceImageCache(1, 3);
        try (var first = cache.acquire("a", "abc")) {
            assertThrows(IllegalStateException.class, () -> cache.acquire("b", "a"));
            assertThrows(IllegalStateException.class, () -> cache.acquire("a", "abcd"));
            assertThrows(IllegalArgumentException.class, () -> cache.acquire("", "x"));
            assertThrows(IllegalArgumentException.class, () -> cache.acquire("c", ""));
            assertEquals(1, cache.liveImages());
            assertEquals(3L, cache.pinnedBytes());
            try (var same = cache.acquire("a", "abc")) {
                assertSame(first.source(), same.source(),
                        "at capacity, a live image is still reusable");
            }
            assertEquals(1, cache.liveImages());
        }
        assertEquals(0L, cache.pinnedBytes());
        try (var recovered = cache.acquire("b", "x")) {
            assertEquals(1, cache.liveImages());
        }
    }

    @Test
    void concurrentReadersInternOnlyOneSource() throws Exception {
        var cache = new UntrustedSourceImageCache();
        CountDownLatch begin = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<Source>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                futures.add(workers.submit(() -> {
                    assertTrue(begin.await(10, TimeUnit.SECONDS));
                    try (var lease = cache.acquire("worker", "version-one")) {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                        return lease.source();
                    }
                }));
            }
            begin.countDown();
            // Not all scheduled tasks can hold a lease at the same time on 8
            // threads; pin one separate lease to prevent an artificial eviction.
            try (var anchor = cache.acquire("worker", "version-one")) {
                release.countDown();
                for (var future : futures) {
                    assertSame(anchor.source(), future.get(10, TimeUnit.SECONDS));
                }
                assertEquals(1, cache.liveImages());
            }
        }
        assertEquals(0, cache.liveImages());
    }
}
