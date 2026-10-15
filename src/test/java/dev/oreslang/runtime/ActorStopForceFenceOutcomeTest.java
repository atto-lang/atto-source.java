package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Force revocation is only provisional until the VM isolation hook finishes.
 * STOP may arrive while that hook is executing. Neither outcome may run
 * arbitrary guest code on the host revoker thread or lose source cleanup.
 */
final class ActorStopForceFenceOutcomeTest {
    @Test
    @Timeout(15)
    void successfulRevokerAfterConcurrentStopPermanentlyRejectsLateSourceResume()
            throws Exception {
        var producer = new OresFuture<Integer>();
        var revokerEntered = new CountDownLatch(1);
        var releaseRevoker = new CountDownLatch(1);
        var sourceEntered = new CountDownLatch(1);
        var turns = new AtomicInteger();

        try (var runtime = new ActorRuntime()) {
            runtime.setForceCancellationTreeHook(request -> {
                revokerEntered.countDown();
                try {
                    if (!releaseRevoker.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("revoker test was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            });
            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                context.runtime().ownCurrentActorFuture(context.runtime().startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) event -> {
                            if (turns.incrementAndGet() != 1) {
                                throw new AssertionError(
                                        "successful force revocation must not admit STOP cleanup");
                            }
                            sourceEntered.countDown();
                            return OresScheduler.await(producer);
                        }));
            });
            actor.send("start");
            assertTrue(sourceEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Boolean> attempt =
                    CompletableFuture.supplyAsync(actor::forceCancel);
            try {
                assertTrue(revokerEntered.await(5, TimeUnit.SECONDS));
                actor.stop();
                assertFalse(actor.done().isDone(),
                        "pending source state must not finalize inside provisional fence");
                assertEquals(1, turns.get());
            } finally {
                releaseRevoker.countDown();
            }
            assertTrue(attempt.get(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            producer.completeFromRuntime(12);
            assertEquals(1, turns.get(), "stale producer completion must be inert");
            assertFalse(producer.isCancelled(), "the external producer owns its Future");
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertTrue(actor.failure().isPresent(),
                    "confirmed force revocation may not publish successful STOP");
        }
    }

    @Test
    @Timeout(15)
    void throwingRevokerAfterConcurrentStopReleasesFenceAndCompletesCleanup()
            throws Exception {
        var producer = new OresFuture<Integer>();
        var cleanup = new OresFuture<Integer>();
        var revokerEntered = new CountDownLatch(1);
        var releaseRevoker = new CountDownLatch(1);
        var sourceEntered = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1);
        var turns = new AtomicInteger();

        try (var runtime = new ActorRuntime()) {
            runtime.setForceCancellationTreeHook(request -> {
                revokerEntered.countDown();
                try {
                    if (!releaseRevoker.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("revoker test was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                throw new IllegalStateException("synthetic atomic revocation failure");
            });
            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                context.runtime().ownCurrentActorFuture(context.runtime().startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) event -> {
                            int turn = turns.incrementAndGet();
                            if (turn == 1) {
                                sourceEntered.countDown();
                                return OresScheduler.await(producer);
                            }
                            if (turn == 2) {
                                assertInstanceOf(CancellationException.class, event.failure());
                                cleanupEntered.countDown();
                                return OresScheduler.await(cleanup);
                            }
                            assertEquals(3, turn);
                            return OresScheduler.done(1);
                        }));
            });
            actor.send("start");
            assertTrue(sourceEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Throwable> attempt =
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            actor.forceCancel();
                            return null;
                        } catch (Throwable failure) {
                            return failure;
                        }
                    });
            try {
                assertTrue(revokerEntered.await(5, TimeUnit.SECONDS));
                actor.stop();
                assertFalse(actor.done().isDone());
                assertEquals(1, turns.get(),
                        "provisionally fenced cleanup cannot run early");
            } finally {
                releaseRevoker.countDown();
            }
            var rejected = assertInstanceOf(IllegalStateException.class,
                    attempt.get(5, TimeUnit.SECONDS));
            assertTrue(rejected.getMessage().contains("revoker failed"));
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone(),
                    "async lexical cleanup must finish before DONE");
            cleanup.completeFromRuntime(1);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(3, turns.get());
            assertTrue(actor.failure().isEmpty(),
                    "failed force revocation may not fabricate an actor terminal error");
            assertFalse(producer.isCancelled());
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertEquals(0, cleanup.pendingRuntimeWaiterCount());
        }
    }
}
