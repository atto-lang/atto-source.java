package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class UntrustedSourceCleanupAdmissionTest {
    // Host-only instrumentation is kept in static test fixtures so the
    // UNTRUSTED actor factory captures no mutable host objects or authority.
    private static final AtomicReference<OresFuture<Integer>> PRODUCER = new AtomicReference<>();
    private static final AtomicReference<OresFuture<Integer>> CLEANUP = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> SUSPENDED = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> CLEANUP_ENTERED = new AtomicReference<>();
    private static final AtomicInteger GUEST_TURNS = new AtomicInteger();
    private static final AtomicReference<OresFuture<Integer>> RESULT = new AtomicReference<>();

    @Test
    void untrustedQuotaWrapperPreservesDeferredCancellationAndStopCleanup()
            throws Exception {
        OresFuture<Integer> sharedProducer = new OresFuture<>();
        OresFuture<Integer> cleanupGate = new OresFuture<>();
        CountDownLatch sourceSuspended = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        PRODUCER.set(sharedProducer);
        CLEANUP.set(cleanupGate);
        SUSPENDED.set(sourceSuspended);
        CLEANUP_ENTERED.set(cleanupEntered);
        GUEST_TURNS.set(0);
        RESULT.set(null);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnUntrusted(factory -> (message, turn) -> {
                OresFuture<Integer> result = turn.runtime().startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                            int turnIndex = GUEST_TURNS.incrementAndGet();
                            if (turnIndex == 1) {
                                assertTrue(resume.initial());
                                SUSPENDED.get().countDown();
                                return OresScheduler.await(PRODUCER.get());
                            }
                            if (turnIndex == 2) {
                                assertInstanceOf(CancellationException.class, resume.failure());
                                assertTrue(OresScheduler.currentTaskInCleanup(),
                                        "quota wrapper must preserve cleanup authorization");
                                CLEANUP_ENTERED.get().countDown();
                                return OresScheduler.await(CLEANUP.get());
                            }
                            assertEquals(3, turnIndex);
                            assertTrue(OresScheduler.currentTaskInCleanup());
                            return OresScheduler.done(91);
                        });
                RESULT.set(turn.runtime().ownCurrentActorFuture(result));
            });
            actor.send("begin");
            assertTrue(sourceSuspended.await(2, TimeUnit.SECONDS));
            // STOP may wait briefly for finalization. Do not hold the test thread
            // while cleanup completes within the untrusted 250ms wall budget.
            CompletableFuture<Void> stopping = CompletableFuture.runAsync(actor::stop);
            assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS),
                    "STOP must admit the already-live untrusted task's cleanup turn");
            assertFalse(actor.done().isDone(),
                    "actor state remains live while authorized cleanup is suspended");
            assertFalse(RESULT.get().isDone(),
                    "deferred cancellation is not yet terminal");
            assertEquals(0, sharedProducer.pendingRuntimeWaiterCount());
            assertFalse(sharedProducer.isCancelled());
            cleanupGate.completeFromRuntime(17);
            stopping.get(3, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(3, GUEST_TURNS.get());
            assertThrows(CancellationException.class,
                    () -> RESULT.get().get(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }
}
