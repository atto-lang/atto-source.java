package dev.oreslang.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorStopTentativeRevocationTest {
    @Test
    void stoppedSourceTaskSurvivesRefusedConcurrentRevocation() throws Exception {
        var source = new OresFuture<Integer>();
        var cleanup = new OresFuture<Integer>();
        var entered = new CountDownLatch(1);
        var fenced = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1);
        var turns = new AtomicInteger();
        try (var runtime = new ActorRuntime()) {
            runtime.setForceCancellationTreeHook(request -> {
                fenced.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) return false;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return false;
            });
            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                context.runtime().ownCurrentActorFuture(context.runtime().startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                            int n = turns.incrementAndGet();
                            if (n == 1) {
                                entered.countDown();
                                return OresScheduler.await(source);
                            }
                            if (n == 2) {
                                assertInstanceOf(CancellationException.class, resume.failure());
                                cleanupEntered.countDown();
                                return OresScheduler.await(cleanup);
                            }
                            assertEquals(3, n);
                            return OresScheduler.done(1);
                        }));
            });
            actor.send("start");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Throwable> attempt = CompletableFuture.supplyAsync(() -> {
                try {
                    actor.forceCancel();
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            try {
                assertTrue(fenced.await(5, TimeUnit.SECONDS));
                actor.stop();
                assertFalse(actor.done().isDone());
                assertEquals(1, turns.get());
            } finally {
                release.countDown();
            }
            assertInstanceOf(IllegalStateException.class, attempt.get(5, TimeUnit.SECONDS));
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone());
            cleanup.completeFromRuntime(1);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(3, turns.get());
            assertTrue(actor.failure().isEmpty());
            assertFalse(source.isCancelled());
            assertEquals(0, source.pendingRuntimeWaiterCount());
            assertEquals(0, cleanup.pendingRuntimeWaiterCount());
        }
    }
}
