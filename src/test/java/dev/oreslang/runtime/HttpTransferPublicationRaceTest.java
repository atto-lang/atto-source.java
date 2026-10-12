package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent transfer publication versus runtime shutdown. The registry's
 * seal is the linearization point: no transfer published after it may escape
 * the close sweep, and every successful registration is included in it.
 */
final class HttpTransferPublicationRaceTest {
    @Test void publicationBeforeSealIsIncludedAndPublicationAfterSealIsRejected() {
        var registry = new NativeHttp.TransferPublicationGate<String>();
        registry.publish("first", "one");
        registry.publish("second", "two");
        assertEquals(2, registry.size());
        assertEquals(new HashSet<>(List.of("one", "two")),
                new HashSet<>(registry.sealAndSnapshot()));
        assertThrows(IllegalStateException.class,
                () -> registry.publish("third", "three"));
        assertEquals(2, registry.size());
        assertEquals(2, registry.sealAndSnapshot().size());
        assertEquals("one", registry.remove("first"));
        assertTrue(registry.remove("second", "two"));
        assertEquals(0, registry.size());
        assertThrows(IllegalStateException.class,
                () -> registry.publish("first", "one"),
                "restarting the registry would resurrect stale authority");
    }

    @Test void concurrentPublishesAndSealHaveExactLinearizableSnapshot() throws Exception {
        final int attempts = 96;
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 24; round++) {
                var registry = new NativeHttp.TransferPublicationGate<Integer>();
                var begin = new CountDownLatch(1);
                var published = new AtomicInteger();
                var rejected = new AtomicInteger();
                var tasks = new ArrayList<java.util.concurrent.Future<?>>();
                for (int n = 0; n < attempts; n++) {
                    final int ticket = n;
                    tasks.add(workers.submit(() -> {
                        begin.await();
                        try {
                            registry.publish(Integer.toString(ticket), ticket);
                            published.incrementAndGet();
                        } catch (IllegalStateException closed) {
                            rejected.incrementAndGet();
                        }
                        return null;
                    }));
                }
                var seal = workers.submit(() -> {
                    begin.await();
                    return registry.sealAndSnapshot();
                });
                begin.countDown();
                List<Integer> snapshot = seal.get(10, TimeUnit.SECONDS);
                for (var t : tasks) t.get(10, TimeUnit.SECONDS);
                assertEquals(attempts, published.get() + rejected.get(),
                        "each concurrent transfer must either publish or reject");
                assertEquals(published.get(), snapshot.size(),
                        "close sweep must observe every successfully published token");
                assertEquals(published.get(), registry.size());
                assertEquals(snapshot.size(), new HashSet<>(snapshot).size(),
                        "tokens in close sweep must be unique");
                assertThrows(IllegalStateException.class,
                        () -> registry.publish("post-shutdown", -1));
            }
        }
    }

    @Test void sealFencesClaimsAsWellAsNewPublications() {
        var registry = new NativeHttp.TransferPublicationGate<String>();
        registry.publish("before", "claimed-before-close");
        assertTrue(registry.claimIfOpen("before", "claimed-before-close"));
        assertEquals(List.of(), registry.sealAndSnapshot());
        assertFalse(registry.claimIfOpen("before", "claimed-before-close"));
        assertThrows(IllegalStateException.class,
                () -> registry.publish("after", "unreachable"));

        var pending = new NativeHttp.TransferPublicationGate<String>();
        pending.publish("stale", "old-incarnation");
        assertEquals(List.of("old-incarnation"), pending.sealAndSnapshot());
        assertFalse(pending.claimIfOpen("stale", "old-incarnation"),
                "a guest cannot claim a published ticket after runtime shutdown");
        assertEquals("old-incarnation", pending.remove("stale"),
                "authorized teardown may still revoke the stale ticket");
    }

    @Test void concurrentClaimsAndShutdownLeaveExactlyTheUnclaimedSnapshot()
            throws Exception {
        final int attempts = 96;
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 16; round++) {
                var registry = new NativeHttp.TransferPublicationGate<Integer>();
                for (int n = 0; n < attempts; n++)
                    registry.publish(Integer.toString(n), n);
                var begin = new CountDownLatch(1);
                var claimed = new AtomicInteger();
                var tasks = new ArrayList<java.util.concurrent.Future<?>>();
                for (int n = 0; n < attempts; n++) {
                    final int token = n;
                    tasks.add(workers.submit(() -> {
                        begin.await();
                        if (registry.claimIfOpen(Integer.toString(token), token))
                            claimed.incrementAndGet();
                        return null;
                    }));
                }
                var closing = workers.submit(() -> {
                    begin.await();
                    return registry.sealAndSnapshot();
                });
                begin.countDown();
                var remaining = closing.get(10, TimeUnit.SECONDS);
                for (var task : tasks) task.get(10, TimeUnit.SECONDS);
                assertEquals(attempts, claimed.get() + remaining.size(),
                        "every ticket must be claimed before seal or retained for teardown");
                assertEquals(remaining.size(), registry.size());
                assertEquals(remaining.size(), new HashSet<>(remaining).size());
                for (int n = 0; n < attempts; n++)
                    assertFalse(registry.claimIfOpen(Integer.toString(n), n),
                            "post-shutdown claims must always fail");
            }
        }
    }

    @Test void staleRevocationCannotRemoveReusedTicketOrForeignExchange() {
        var registry = new NativeHttp.TransferPublicationGate<Object>();
        Object original = new Object();
        Object replacement = new Object();
        Object foreign = new Object();

        registry.publish("ticket", original);
        assertFalse(registry.remove("ticket", foreign),
                "foreign exchange identity must not remove the original");
        assertSame(original, registry.get("ticket"));
        assertTrue(registry.remove("ticket", original));
        registry.publish("ticket", replacement);

        assertFalse(registry.remove("ticket", original),
                "a stale actor-done callback must not revoke a replacement");
        assertSame(replacement, registry.get("ticket"));
        assertEquals(1, registry.sealAndSnapshot().size());
        assertSame(replacement, registry.get("ticket"));
        assertTrue(registry.remove("ticket", replacement),
                "authorized cleanup still removes the exact published exchange");
        assertEquals(0, registry.size());
    }

    @Test void duplicatePublicationCannotOverwriteOriginalTransfer() {
        var registry = new NativeHttp.TransferPublicationGate<String>();
        registry.publish("ticket", "original-actor");
        assertThrows(IllegalStateException.class,
                () -> registry.publish("ticket", "foreign-actor"));
        assertEquals("original-actor", registry.get("ticket"));
        assertEquals(List.of("original-actor"), registry.sealAndSnapshot());
    }
}
