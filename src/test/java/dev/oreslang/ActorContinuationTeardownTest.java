package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import dev.oreslang.runtime.OresFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorContinuationTeardownTest {
    @Test
    void unsupportedCleanupAwareActorTaskFailsClosedBeforeConsumingSerialTicket()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch denied = new CountDownLatch(1);
            AtomicInteger executed = new AtomicInteger();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                UnsupportedOperationException failure = assertThrows(
                        UnsupportedOperationException.class,
                        () -> context.runtime().startActorTask(
                                (OresScheduler.CleanupAwareTask<Void>) resume -> {
                                    executed.incrementAndGet();
                                    return OresScheduler.done(null);
                                }));
                assertTrue(failure.getMessage().contains("STOP-safe ticketed"),
                        "guest actor cleanup must not silently fail after STOP");
                denied.countDown();
                context.self().stop();
            });
            ref.send(1);
            assertTrue(denied.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, executed.get());
            assertTrue(ref.failure().isEmpty());
        }
    }

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

    @Test
    void producerCompletionRacingActorStopNeverPublishesTwiceOrRevivesActor()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(2, 4, 64, 32);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            for (int attempt = 0; attempt < 12; attempt++) {
                CompletableFuture<Integer> stage = new CompletableFuture<>();
                OresFuture<Integer> awaited = OresFuture.from(stage);
                CountDownLatch firstSegment = new CountDownLatch(1);
                CountDownLatch race = new CountDownLatch(1);
                AtomicInteger executions = new AtomicInteger();
                AtomicInteger publications = new AtomicInteger();
                AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
                var ref = runtime.<Integer>spawnShared(() -> (mail, context) ->
                        context.runtime().startActorTask(resume -> {
                            int pc = executions.getAndIncrement();
                            if (pc == 0) {
                                firstSegment.countDown();
                                return OresScheduler.await(awaited);
                            }
                            if (pc != 1) {
                                throw new AssertionError("producer resumed source twice");
                            }
                            publications.incrementAndGet();
                            return OresScheduler.done(null);
                        }));
                ref.send(1);
                assertTrue(firstSegment.await(5, TimeUnit.SECONDS),
                        "actor source task never reached await: " + attempt);
                Thread producer = Thread.ofPlatform().start(() -> {
                    try {
                        race.await();
                        stage.complete(7);
                    } catch (Throwable failure) {
                        backgroundFailure.compareAndSet(null, failure);
                    }
                });
                Thread canceller = Thread.ofPlatform().start(() -> {
                    try {
                        race.await();
                        ref.stop();
                    } catch (Throwable failure) {
                        backgroundFailure.compareAndSet(null, failure);
                    }
                });
                race.countDown();
                producer.join();
                canceller.join();
                if (backgroundFailure.get() != null) {
                    fail("producer/stop race failed", backgroundFailure.get());
                }
                assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS),
                        "actor failed to terminate after cancellation race " + attempt);
                assertTrue(executions.get() <= 2,
                        "stale/duplicate source continuation executed: " + attempt);
                assertTrue(publications.get() <= 1,
                        "source result published more than once: " + attempt);
                assertTrue(ref.failure().isEmpty(),
                        "normal cancellation poisoned the actor: " + ref.failure());
            }
        }
    }
}
