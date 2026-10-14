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
    @Test
    void untrustedQuotaWrapperPreservesDeferredCancellationAndStopCleanup()
            throws Exception {
        OresFuture<Integer> sharedProducer = new OresFuture<>();
        OresFuture<Integer> cleanupGate = new OresFuture<>();
        CountDownLatch sourceSuspended = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        AtomicInteger guestTurns = new AtomicInteger();
        AtomicReference<OresFuture<Integer>> resultRef = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnUntrusted(factory -> (message, turn) -> {
                OresFuture<Integer> result = turn.runtime().startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                            int turnIndex = guestTurns.incrementAndGet();
                            if (turnIndex == 1) {
                                assertTrue(resume.initial());
                                sourceSuspended.countDown();
                                return OresScheduler.await(sharedProducer);
                            }
                            if (turnIndex == 2) {
                                assertInstanceOf(CancellationException.class, resume.failure());
                                assertTrue(OresScheduler.currentTaskInCleanup(),
                                        "quota wrapper must preserve cleanup authorization");
                                cleanupEntered.countDown();
                                return OresScheduler.await(cleanupGate);
                            }
                            assertEquals(3, turnIndex);
                            assertTrue(OresScheduler.currentTaskInCleanup());
                            return OresScheduler.done(91);
                        });
                resultRef.set(turn.runtime().ownCurrentActorFuture(result));
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
            assertFalse(resultRef.get().isDone(),
                    "deferred cancellation is not yet terminal");
            assertEquals(0, sharedProducer.pendingRuntimeWaiterCount());
            assertFalse(sharedProducer.isCancelled());
            cleanupGate.completeFromRuntime(17);
            stopping.get(3, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(3, guestTurns.get());
            assertThrows(CancellationException.class,
                    () -> resultRef.get().get(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }
}
