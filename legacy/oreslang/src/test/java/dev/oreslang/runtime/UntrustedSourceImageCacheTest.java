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
            // The cache entry was evicted. Graal may still independently
            // intern the underlying immutable Source, so object inequality
            // is NOT an executable-image reclamation proof.
            assertNotNull(newGeneration.source());
            assertEquals(1, cache.liveImages());
        }
        assertEquals(0, cache.liveImages());
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
            // Polyglot may intern immutable Source wrappers, but each cache
            // owns its own authorization, quotas and image-lease lifetime.
            assertEquals("core.ores", a.source().getName());
            assertEquals("core.ores", b.source().getName());
            a.close();
            assertEquals(0, one.liveImages());
            assertEquals(1, two.liveImages());
            assertEquals(7L, two.pinnedBytes());
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
    void oneThousandActorLeasesKeepExactlyOneImmutableImage() {
        var cache = new UntrustedSourceImageCache();
        List<UntrustedSourceImageCache.Lease> actors = new ArrayList<>();
        Source expected = null;
        try {
            for (int i = 0; i < 1_000; i++) {
                var lease = cache.acquire("worker.ores", "same-compiled-program");
                if (expected == null) expected = lease.source();
                else assertSame(expected, lease.source());
                actors.add(lease);
            }
            assertEquals(1, cache.liveImages());
            assertEquals(21L, cache.pinnedBytes());
        } finally {
            for (int i = actors.size() - 1; i >= 0; --i) actors.get(i).close();
        }
        assertEquals(0, cache.liveImages());
        assertEquals(0L, cache.pinnedBytes());
    }

    @Test
    void rejectsMalformedUnicodeAmbiguousSourceNamesAndOversizedUtf8() {
        var cache = new UntrustedSourceImageCache();
        String unpairedSurrogate = String.valueOf((char) 0xD800);
        assertThrows(IllegalArgumentException.class,
                () -> cache.acquire("worker.ores", unpairedSurrogate),
                "malformed Unicode must not be replaced before source hashing");
        assertThrows(IllegalArgumentException.class,
                () -> cache.acquire("unit\nbad", "valid"),
                "untrusted logical module names cannot contain control characters");
        assertThrows(IllegalArgumentException.class,
                () -> cache.acquire("unit" + unpairedSurrogate, "valid"));
        assertThrows(IllegalArgumentException.class,
                () -> cache.acquire("worker.ores", "€".repeat(400_000)),
                "UTF-8 byte quota must take precedence over UTF-16 character count");
        assertEquals(0, cache.liveImages());
        assertEquals(0L, cache.pinnedBytes());
    }

    @Test
    void failedLeaseAccountingRemainsRetryableBeforeConfirmedRetirement() throws Exception {
        var cache = new UntrustedSourceImageCache(1, 128);
        var lease = cache.acquire("worker", "immutable-source");
        var entryField = UntrustedSourceImageCache.Lease.class.getDeclaredField("entry");
        entryField.setAccessible(true);
        var entry = entryField.get(lease);
        var countField = entry.getClass().getDeclaredField("leases");
        countField.setAccessible(true);
        try {
            countField.setInt(entry, 0); // deterministic corrupted-accounting injection
            assertThrows(IllegalStateException.class, lease::close);
            assertNotNull(lease.source(),
                    "a failed release cannot advertise that the source lease was retired");
            assertEquals(1, cache.liveImages());
            countField.setInt(entry, 1);
            lease.close();
            lease.close(); // idempotent only after confirmed cache decrement
            assertThrows(IllegalStateException.class, lease::source);
            assertEquals(0, cache.liveImages());
            assertEquals(0L, cache.pinnedBytes());
        } finally {
            // Restore clean accounting for assertion/cleanup failures too.
            if (cache.liveImages() != 0) {
                countField.setInt(entry, 1);
                lease.close();
            }
        }
    }

    @Test
    void rejectedUnicodeAliasesDoNotConsumeOrEvictImageQuota() {
        var cache = new UntrustedSourceImageCache(1, 64);
        try (var canonical = cache.acquire("lib/é.ores", "code")) {
            assertNotNull(canonical.source());
            assertEquals(1, cache.liveImages());
            assertEquals(4L, cache.pinnedBytes());
            for (int attempt = 0; attempt < 100; attempt++) {
                assertThrows(IllegalArgumentException.class,
                        () -> cache.acquire("lib/e\u0301.ores", "code"));
                assertEquals(1, cache.liveImages());
                assertEquals(4L, cache.pinnedBytes());
            }
            try (var same = cache.acquire("lib/é.ores", "code")) {
                assertSame(canonical.source(), same.source(),
                        "canonical code image can still be reused");
            }
            assertEquals(1, cache.liveImages());
            assertEquals(4L, cache.pinnedBytes());
        }
        assertEquals(0, cache.liveImages());
        assertEquals(0L, cache.pinnedBytes());
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
    @Test
    void concurrentClosersCannotDoubleReleaseSameLease() throws Exception {
        var cache = new UntrustedSourceImageCache(1, 128);
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int round = 0; round < 32; round++) {
                var lease = cache.acquire("worker", "same-image");
                try {
                    CountDownLatch start = new CountDownLatch(1);
                    List<java.util.concurrent.Future<?>> closes = new ArrayList<>();
                    for (int i = 0; i < 8; i++) {
                        closes.add(workers.submit(() -> {
                            assertTrue(start.await(10, TimeUnit.SECONDS));
                            lease.close();
                            return null;
                        }));
                    }
                    start.countDown();
                    for (var close : closes) close.get(10, TimeUnit.SECONDS);
                    assertEquals(0, cache.liveImages(),
                            "a race among closers must retire exactly one image");
                    assertEquals(0L, cache.pinnedBytes(),
                            "the budget must be refunded once, never more");
                    assertThrows(IllegalStateException.class, lease::source);
                } finally {
                    lease.close();
                }
            }
        }
    }

}
