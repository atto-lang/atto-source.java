package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import dev.oreslang.runtime.OresFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorContinuationTeardownTest {
    @Test
    void gracefulStopCancelsQueuedSourceTasksWithoutRunningThem() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            int tasks = 256;
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger taskBodies = new AtomicInteger();

            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                for (int i = 0; i < tasks; i++) {
                    context.runtime().startActorTask(resume -> {
                        taskBodies.incrementAndGet();
                        return OresScheduler.done(null);
                    });
                }
                context.self().stop();
            });

            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
            assertEquals(0, taskBodies.get(),
                    "source continuations queued behind a stopped actor must not run");
        }
    }

    @Test
    void sourceAwaitProducerCompletingAfterStopCannotReenterOldActor() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CompletableFuture<Integer> producerStage = new CompletableFuture<>();
            OresFuture<Integer> producer = OresFuture.from(producerStage);
            CountDownLatch suspended = new CountDownLatch(1);
            AtomicInteger segments = new AtomicInteger();
            AtomicInteger published = new AtomicInteger();
            var ref = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                context.runtime().startActorTask(resume -> {
                    int segment = segments.getAndIncrement();
                    if (segment == 0) {
                        suspended.countDown();
                        return OresScheduler.await(producer);
                    }
                    // A stale Future cannot bring a stopped incarnation back
                    // into guest code to publish an already-revoked result.
                    published.incrementAndGet();
                    return OresScheduler.done(null);
                });
            });
            ref.send(1);
            assertTrue(suspended.await(5, TimeUnit.SECONDS),
                    "the source scheduler did not enter the awaited segment");
            ref.stop();
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, segments.get());
            // Structured teardown may cancel the host stage; either a late
            // completion or an already-cancelled producer must be harmless.
            producerStage.complete(7);
            assertEquals(0, published.get(),
                    "the producer must not execute a guest callback on its own thread");
            assertEquals(1, segments.get(),
                    "terminally stopped actor must not execute a stale source continuation");
        }
    }
}
