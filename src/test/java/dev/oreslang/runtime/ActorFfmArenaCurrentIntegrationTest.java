package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Current-integration proof for deterministic actor-owned FFM data. */
@Timeout(20)
final class ActorFfmArenaCurrentIntegrationTest {
    @Test
    void previewFfmOnPre25RuntimeIsRejectedFailClosed() {
        if (Runtime.version().feature() < 25) {
            assertFalse(ExplicitNativeI64Region.isAvailable(),
                    "preview java.lang.foreign classes must not satisfy the Java 25 FFM gate");
        }
    }

    @Test
    void persistentArenaSurvivesTurnsAndLoanPinsPhysicalRetirement() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var arenaRef = new AtomicReference<ActorArena>();
        var sliceRef = new AtomicReference<ActorRuntime.ActorMemorySlice>();
        var persistentRef = new AtomicReference<ExplicitNativeI64Region.Slot>();
        var loanRef = new AtomicReference<ExplicitNativeI64Region.Loan>();
        var scratchRef = new AtomicReference<ExplicitNativeI64Region.Slot>();
        var firstDone = new CountDownLatch(1);
        var secondDone = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var memory = turn.localMemory().orElseThrow();
                sliceRef.compareAndSet(null, memory);

                if ("first".equals(message)) {
                    var arena = memory.newActorArena(2, 2);
                    arenaRef.set(arena);
                    persistentRef.set(arena.allocatePersistent(40L));
                    try (var scratch = arena.beginTurn()) {
                        var ephemeral = scratch.allocate(7L);
                        assertEquals(7L, scratch.read(ephemeral));
                        scratchRef.set(ephemeral);
                    }
                    assertEquals(32L, memory.usedBytes(),
                            "two 2-slot FFM regions must remain charged while actor lives");
                    firstDone.countDown();
                    return;
                }

                var arena = arenaRef.get();
                assertSame(memory, sliceRef.get());
                assertEquals(40L, arena.readPersistent(persistentRef.get()),
                        "persistent FFM state must survive a mailbox/carrier boundary");
                try (var scratch = arena.beginTurn()) {
                    assertThrows(IllegalStateException.class,
                            () -> scratch.read(scratchRef.get()),
                            "prior-turn scratch handle must be generation-stale");
                    var current = scratch.allocate(2L);
                    assertEquals(2L, scratch.read(current));
                }
                arena.writePersistent(persistentRef.get(), 42L);
                assertEquals(42L, arena.readPersistent(persistentRef.get()));
                loanRef.set(arena.borrowPersistent(persistentRef.get()));
                secondDone.countDown();
                turn.self().stop();
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("first");
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));
            actor.send("second");
            assertTrue(secondDone.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());

            assertTrue(arenaRef.get().closed(), "logical retirement must revoke arena");
            assertFalse(arenaRef.get().physicallyClosed(),
                    "persistent loan must pin only its native FFM region");
            assertEquals(16L, sliceRef.get().usedBytes(),
                    "scratch FFM bytes must be physically released at actor retirement");
            assertThrows(IllegalStateException.class, loanRef.get()::read,
                    "late completion lease must not dereference retired actor memory");

            loanRef.get().close();
            loanRef.get().close();
            assertTrue(arenaRef.get().physicallyClosed());
            assertEquals(0L, sliceRef.get().usedBytes(),
                    "last native loan must refund physical bytes exactly once");
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void externalCompletionBridgePinsLifetimeUntilOffCarrierCompletion() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var arenaRef = new AtomicReference<ActorArena>();
        var sliceRef = new AtomicReference<ActorRuntime.ActorMemorySlice>();
        var trackedRef = new AtomicReference<CompletableFuture<Long>>();
        var completion = new CompletableFuture<Long>();
        var registered = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var memory = turn.localMemory().orElseThrow();
                sliceRef.set(memory);
                var arena = memory.newActorArena(1, 1);
                arenaRef.set(arena);
                trackedRef.set(arena.trackExternalCompletion(completion));
                registered.countDown();
                turn.self().stop();
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());

            assertTrue(arenaRef.get().closed(), "retirement must revoke arena authority");
            assertFalse(arenaRef.get().physicallyClosed(),
                    "pending completion must pin both actor-native regions");
            assertEquals(1, arenaRef.get().outstandingExternalPins());
            assertEquals(16L, sliceRef.get().usedBytes(),
                    "both scratch and persistent FFM regions stay charged while pending");

            assertTrue(completion.complete(7L));
            assertEquals(7L, trackedRef.get().get(5, TimeUnit.SECONDS));
            assertEquals(0, arenaRef.get().outstandingExternalPins());
            assertTrue(arenaRef.get().physicallyClosed());
            assertEquals(0L, sliceRef.get().usedBytes());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void explicitArenaPinBlocksBothPhysicalRegionsAndCannotReadMemory() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");
        var arenaRef = new AtomicReference<ActorArena>();
        var leaseRef = new AtomicReference<ActorArena.ExternalLease>();
        var sliceRef = new AtomicReference<ActorRuntime.ActorMemorySlice>();
        var admitted = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var slice = turn.localMemory().orElseThrow();
                sliceRef.set(slice);
                var arena = slice.newActorArena(1, 1);
                arenaRef.set(arena);
                leaseRef.set(arena.pinExternal());
                admitted.countDown();
                turn.self().stop();
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("pin");
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(16L, sliceRef.get().usedBytes());
            assertFalse(arenaRef.get().physicallyClosed());
            assertEquals(1, arenaRef.get().outstandingExternalPins());
            // A completion carrier owns only a lifetime token.
            for (var method : ActorArena.ExternalLease.class.getMethods()) {
                assertFalse(method.getName().startsWith("read")
                        || method.getName().startsWith("write"),
                        "external lease cannot expose actor state");
            }
            leaseRef.get().close();
            leaseRef.get().close();
            assertEquals(0, arenaRef.get().outstandingExternalPins());
            assertTrue(arenaRef.get().physicallyClosed());
            assertEquals(0L, sliceRef.get().usedBytes());
        }
    }

    @Test
    void concurrentOffCarrierLeaseReleaseIsIdempotentAndRefundsOnce() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var arenaRef = new AtomicReference<ActorArena>();
        var firstRef = new AtomicReference<ActorArena.ExternalLease>();
        var secondRef = new AtomicReference<ActorArena.ExternalLease>();
        var sliceRef = new AtomicReference<ActorRuntime.ActorMemorySlice>();
        var admitted = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var slice = turn.localMemory().orElseThrow();
                sliceRef.set(slice);
                var arena = slice.newActorArena(1, 1);
                arenaRef.set(arena);
                firstRef.set(arena.pinExternal());
                secondRef.set(arena.pinExternal());
                admitted.countDown();
                turn.self().stop();
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("pin-twice");
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, arenaRef.get().outstandingExternalPins());
            assertFalse(arenaRef.get().physicallyClosed());
            assertEquals(16L, sliceRef.get().usedBytes());

            var release = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            Thread[] closers = new Thread[8];
            for (int i = 0; i < closers.length; i++) {
                var lease = (i & 1) == 0 ? firstRef.get() : secondRef.get();
                closers[i] = new Thread(() -> {
                    try {
                        release.await();
                        lease.close();
                        lease.close();
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                });
                closers[i].start();
            }
            release.countDown();
            for (Thread closer : closers) closer.join(5_000);
            for (Thread closer : closers) assertFalse(closer.isAlive(), "lease closer hung");

            assertNull(failure.get(), () -> "off-carrier lease close failed: " + failure.get());
            assertEquals(0, arenaRef.get().outstandingExternalPins());
            assertTrue(arenaRef.get().physicallyClosed());
            assertEquals(0L, sliceRef.get().usedBytes(),
                    "duplicate/concurrent close must refund native bytes exactly once");
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void scratchScopeCannotEscapeMailboxBoundary() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var arenaRef = new AtomicReference<ActorArena>();
        var sliceRef = new AtomicReference<ActorRuntime.ActorMemorySlice>();
        var ran = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var memory = turn.localMemory().orElseThrow();
                sliceRef.set(memory);
                var arena = memory.newActorArena(1, 1);
                arenaRef.set(arena);
                var scratch = arena.beginTurn();
                scratch.allocate(99L);
                // Deliberately omit scratch.close(); runtime boundary must fail-stop.
                ran.countDown();
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("escape");
            assertTrue(ran.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            var failure = actor.failure().orElseThrow();
            assertTrue(failure.toString().contains("scratch arena scope escaped"),
                    () -> "unexpected actor failure: " + failure);
            assertTrue(arenaRef.get().closed());
            assertTrue(arenaRef.get().physicallyClosed());
            assertEquals(0L, sliceRef.get().usedBytes());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }
}
