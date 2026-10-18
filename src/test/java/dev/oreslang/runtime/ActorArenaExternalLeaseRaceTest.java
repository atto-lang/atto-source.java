package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Adversarial external-close race, independent of the source-free matrix. */
@Timeout(30)
final class ActorArenaExternalLeaseRaceTest {
    @Test
    void duplicateConcurrentCallbacksRefundEachLifetimeOwnerOnlyOnce() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");
        var arena = new AtomicReference<ActorArena>();
        var leases = new AtomicReference<List<ActorArena.ExternalLease>>();
        var registered = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnShared(() -> (message, turn) -> {
                var owned = turn.localMemory().orElseThrow().newActorArena(1, 1);
                arena.set(owned);
                leases.set(List.of(owned.acquireExternalLease(),
                                   owned.acquireExternalLease()));
                registered.countDown();
                turn.self().stop();
            });
            ref.ready().get(5, TimeUnit.SECONDS);
            ref.send("race");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, arena.get().outstandingExternalLeases());
            assertFalse(arena.get().physicallyClosed());

            final int carriers = 16;
            var ready = new CountDownLatch(carriers);
            var go = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var callbacks = new java.util.ArrayList<Thread>();
            for (int i = 0; i < carriers; ++i) {
                final boolean reverse = (i & 1) != 0;
                var callback = new Thread(() -> {
                    ready.countDown();
                    try {
                        if (!go.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("callback start barrier expired");
                        for (int round = 0; round < 4; ++round) {
                            var pinned = leases.get();
                            if (reverse) {
                                pinned.get(1).close();
                                pinned.get(0).close();
                            } else {
                                pinned.get(0).close();
                                pinned.get(1).close();
                            }
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                }, "actor-lease-callback-" + i);
                callbacks.add(callback);
                callback.start();
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                go.countDown();
            }
            for (var callback : callbacks) {
                callback.join(5000);
                assertFalse(callback.isAlive(), "callback stalled during close");
            }
            assertNull(failure.get(), () -> "external callback failure: " + failure.get());
            assertEquals(0, arena.get().outstandingExternalLeases());
            assertTrue(arena.get().physicallyClosed(),
                    "last callback must make both FFM regions physically quiescent");
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }
}
