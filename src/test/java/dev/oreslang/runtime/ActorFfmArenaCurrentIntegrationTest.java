package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Current-integration proof for deterministic actor-owned FFM data. */
@Timeout(20)
final class ActorFfmArenaCurrentIntegrationTest {
    @Test
    void externalLifetimeAuthorityIsActorLevelOnly() {
        assertThrows(NoSuchMethodException.class,
                () -> ActorArena.class.getMethod("pinExternal"),
                "persistent-only external pin would let scratch reclaim too early");
        assertThrows(NoSuchMethodException.class,
                () -> ActorArena.class.getMethod(
                        "trackExternalCompletion", java.util.concurrent.CompletableFuture.class),
                "completion tracking must not silently pin only one arena region");
        assertDoesNotThrow(() -> ActorArena.class.getMethod("acquireExternalLease"));

        for (var method : ActorArena.ExternalLease.class.getDeclaredMethods()) {
            assertFalse(method.getName().startsWith("read")
                            || method.getName().startsWith("write")
                            || method.getName().startsWith("borrow"),
                    () -> "lifetime-only external lease leaked memory authority: " + method);
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
    void externalLeasePinsPhysicalArenaWithoutRetainingMemoryAuthority() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var arenaRef = new AtomicReference<ActorArena>();
        var sliceRef = new AtomicReference<ActorRuntime.ActorMemorySlice>();
        var leaseRef = new AtomicReference<ActorArena.ExternalLease>();
        var registered = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var memory = turn.localMemory().orElseThrow();
                sliceRef.set(memory);
                var arena = memory.newActorArena(2, 2);
                arenaRef.set(arena);
                arena.allocatePersistent(123L);
                try (var scratch = arena.beginTurn()) {
                    scratch.allocate(456L);
                }
                leaseRef.set(arena.acquireExternalLease());
                assertEquals(1, arena.outstandingExternalLeases());
                registered.countDown();
                turn.self().stop();
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("quiesce");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());

            var arena = arenaRef.get();
            var memory = sliceRef.get();
            assertTrue(arena.closed(), "logical retirement must revoke both FFM regions");
            assertFalse(arena.physicallyClosed(),
                    "external callback lifetime must pin physical native storage");
            assertEquals(1, arena.outstandingExternalLeases());
            assertEquals(32L, memory.usedBytes(),
                    "both two-slot regions remain charged until callback quiescence");

            var releaseFailure = new AtomicReference<Throwable>();
            Thread callbackCarrier = new Thread(() -> {
                try {
                    leaseRef.get().close();
                    leaseRef.get().close(); // idempotent/retry-safe cleanup
                } catch (Throwable failure) {
                    releaseFailure.set(failure);
                }
            }, "actor-arena-external-release");
            callbackCarrier.start();
            callbackCarrier.join(5000);
            assertFalse(callbackCarrier.isAlive(), "external lease release stalled");
            assertNull(releaseFailure.get(), () -> "external lease release failed: " + releaseFailure.get());
            assertEquals(0, arena.outstandingExternalLeases());
            assertTrue(arena.physicallyClosed(),
                    "last lifetime-only lease must trigger deterministic FFM reclaim");
            assertEquals(0L, memory.usedBytes());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void sourceContinuationPinsArenaAcrossSuspendAndReleasesOnSettlement() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var arenaRef = new AtomicReference<ActorArena>();
        var taskRef = new AtomicReference<OresFuture<Integer>>();
        var sourceGate = new OresFuture<Integer>();
        var entered = new CountDownLatch(1);
        var resumed = new CountDownLatch(1);
        var turns = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var arena = turn.localMemory().orElseThrow().newActorArena(1, 1);
                arenaRef.set(arena);
                arena.allocatePersistent(77L);
                taskRef.set(runtime.startActorTask(resume -> {
                    int step = turns.getAndIncrement();
                    if (step == 0) {
                        entered.countDown();
                        return OresScheduler.await(sourceGate);
                    }
                    assertEquals(1, step);
                    assertEquals(9, resume.value());
                    resumed.countDown();
                    return OresScheduler.done(10);
                }));
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("start");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, arenaRef.get().outstandingExternalLeases(),
                    "suspended source continuation must hold lifetime-only arena authority");

            sourceGate.completeFromRuntime(9);
            assertTrue(resumed.await(5, TimeUnit.SECONDS));
            assertEquals(10, taskRef.get().get(5, TimeUnit.SECONDS));
            assertEquals(0, arenaRef.get().outstandingExternalLeases(),
                    "terminal source task must release its arena lifetime lease exactly once");

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(arenaRef.get().physicallyClosed());
        }
    }

    @Test
    void arenaCreatedAfterSourceTaskAdmissionIsDynamicallyPinned() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var arenaRef = new AtomicReference<ActorArena>();
        var taskRef = new AtomicReference<OresFuture<Integer>>();
        var sourceGate = new OresFuture<Integer>();
        var admitted = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                taskRef.set(runtime.startActorTask(resume -> {
                    if (resume.initial()) {
                        admitted.countDown();
                        return OresScheduler.await(sourceGate);
                    }
                    return OresScheduler.done(resume.value());
                }));

                // Deliberately allocate after task admission. The active task
                // lease must be extended to this arena before it is returned.
                var arena = turn.localMemory().orElseThrow().newActorArena(1, 1);
                arena.allocatePersistent(88L);
                arenaRef.set(arena);
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("start");
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            assertNotNull(arenaRef.get());
            assertEquals(1, arenaRef.get().outstandingExternalLeases(),
                    "allocation order must not bypass source-task quiescence");

            sourceGate.completeFromRuntime(12);
            assertEquals(12, taskRef.get().get(5, TimeUnit.SECONDS));
            assertEquals(0, arenaRef.get().outstandingExternalLeases());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(arenaRef.get().physicallyClosed());
        }
    }

    @Test
    void stoppedLexicalCleanupRetainsArenaLeaseUntilCleanupSettles() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var arenaRef = new AtomicReference<ActorArena>();
        var taskRef = new AtomicReference<OresFuture<Integer>>();
        var sourceGate = new OresFuture<Integer>();
        var cleanupGate = new OresFuture<Integer>();
        var originalEntered = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1);
        var turns = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var arena = turn.localMemory().orElseThrow().newActorArena(1, 1);
                arenaRef.set(arena);
                arena.allocatePersistent(123L);
                taskRef.set(runtime.startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                            int step = turns.getAndIncrement();
                            if (step == 0) {
                                originalEntered.countDown();
                                return OresScheduler.await(sourceGate);
                            }
                            if (step == 1) {
                                assertTrue(OresScheduler.currentTaskInCleanup());
                                cleanupEntered.countDown();
                                return OresScheduler.await(cleanupGate);
                            }
                            assertEquals(2, step);
                            assertTrue(OresScheduler.currentTaskInCleanup());
                            assertEquals(47, resume.value());
                            return OresScheduler.done(1);
                        }));
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("start");
            assertTrue(originalEntered.await(5, TimeUnit.SECONDS));
            assertEquals(1, arenaRef.get().outstandingExternalLeases());

            assertTrue(actor.cancel());
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone(),
                    "actor finalization must wait for admitted lexical cleanup");
            assertEquals(1, arenaRef.get().outstandingExternalLeases(),
                    "cleanup await must retain the same lifetime-only arena lease");

            cleanupGate.completeFromRuntime(47);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(taskRef.get().isCancelled());
            assertEquals(0, arenaRef.get().outstandingExternalLeases());
            assertTrue(arenaRef.get().physicallyClosed());
            assertEquals(3, turns.get());

            sourceGate.completeFromRuntime(99);
            assertEquals(3, turns.get(),
                    "late producer completion cannot replay source after STOP");
        }
    }

    @Test
    void failedExternalLeaseAccountingCanBeRetriedAfterPhysicalRelease() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");
        var arenaRef = new AtomicReference<ActorArena>();
        var leaseRef = new AtomicReference<ActorArena.ExternalLease>();
        var registered = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var arena = turn.localMemory().orElseThrow().newActorArena(1, 1);
                arenaRef.set(arena);
                leaseRef.set(arena.acquireExternalLease());
                registered.countDown();
                turn.self().stop();
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("pin");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, arenaRef.get().outstandingExternalLeases());

            // The two FFM pins can retire successfully while the actor-level
            // accounting step fails. Reusing that same completion token must
            // remain possible; never set accounted before decrement succeeds.
            var count = ActorArena.class.getDeclaredField("externalLeases");
            count.setAccessible(true);
            count.setInt(arenaRef.get(), 0);
            try {
                assertThrows(IllegalStateException.class, leaseRef.get()::close);
            } finally {
                count.setInt(arenaRef.get(), 1);
            }
            leaseRef.get().close();
            leaseRef.get().close();
            assertEquals(0, arenaRef.get().outstandingExternalLeases());
            assertTrue(arenaRef.get().physicallyClosed());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }


    @Test
    void concurrentExternalCompletionCarriersRetireBothRegionsExactlyOnce() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        final int carriers = 8;
        final int leasesPerCarrier = 8;
        var arenaRef = new AtomicReference<ActorArena>();
        var sliceRef = new AtomicReference<ActorRuntime.ActorMemorySlice>();
        var leasesRef = new AtomicReference<List<ActorArena.ExternalLease>>();
        var registered = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var memory = turn.localMemory().orElseThrow();
                var arena = memory.newActorArena(2, 2);
                arenaRef.set(arena);
                sliceRef.set(memory);
                var issued = new ArrayList<ActorArena.ExternalLease>();
                for (int i = 0; i < carriers * leasesPerCarrier; i++) {
                    issued.add(arena.acquireExternalLease());
                }
                leasesRef.set(List.copyOf(issued));
                registered.countDown();
                turn.self().stop();
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("suspend-io");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
            assertEquals(carriers * leasesPerCarrier,
                    arenaRef.get().outstandingExternalLeases());
            assertFalse(arenaRef.get().physicallyClosed());
            assertEquals(32L, sliceRef.get().usedBytes());

            var start = new CountDownLatch(1);
            var done = new CountDownLatch(carriers);
            var firstError = new AtomicReference<Throwable>();
            var workers = new ArrayList<Thread>();
            for (int carrier = 0; carrier < carriers; carrier++) {
                final int offset = carrier * leasesPerCarrier;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int index = offset; index < offset + leasesPerCarrier; index++) {
                            var token = leasesRef.get().get(index);
                            token.close();
                            token.close(); // Duplicate callback must not double-refund.
                        }
                    } catch (Throwable failure) {
                        firstError.compareAndSet(null, failure);
                    } finally {
                        done.countDown();
                    }
                }, "actor-arena-completion-" + carrier);
                workers.add(worker);
                worker.start();
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "off-carrier cleanup stalled");
            for (Thread worker : workers) worker.join(1000);
            assertNull(firstError.get(), () -> "external completion failed: " + firstError.get());
            assertEquals(0, arenaRef.get().outstandingExternalLeases());
            assertTrue(arenaRef.get().physicallyClosed());
            assertEquals(0L, sliceRef.get().usedBytes());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }



    @Test
    void escapingTaskPublishedBeforeArenaPinsArenaCreatedLater() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var arenaRef = new AtomicReference<ActorArena>();
        var admitted = new CountDownLatch(1);
        OresFuture<Integer> pending = new OresFuture<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                runtime.startActorTask(resume -> OresScheduler.await(pending));
                var arena = turn.localMemory().orElseThrow().newActorArena(1, 1);
                arenaRef.set(arena);
                assertEquals(1, arena.outstandingExternalLeases(),
                        "an already-escaping task must pin an arena created later in the same incarnation");
                admitted.countDown();
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("publish-before-arena");
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            assertEquals(1, arenaRef.get().outstandingExternalLeases());
            assertTrue(actor.cancel());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, arenaRef.get().outstandingExternalLeases());
            assertTrue(arenaRef.get().physicallyClosed());
            pending.completeFromRuntime(1);
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
