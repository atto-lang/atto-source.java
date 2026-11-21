package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresScheduler;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Producer-settlement, STOP, and cancellation-injection races must never replay
 * actor guest frames or execute cleanup outside the authorized actor lane.
 */
@Timeout(60)
final class ActorCleanupStopRaceStressTest {
    @Test
    void producerSettlementVersusStopCannotReplayLexicalCleanup() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(2, 4, 64, 32);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            for (int attempt = 0; attempt < 24; attempt++) {
                AtomicReference<OresFuture.Callback<Integer>> producer =
                        new AtomicReference<>();
                OresFuture<Integer> awaited = OresFuture.fromCallback(producer::set);
                AtomicInteger segments = new AtomicInteger();
                AtomicInteger cleanupRuns = new AtomicInteger();
                CountDownLatch awaiting = new CountDownLatch(1);
                CountDownLatch race = new CountDownLatch(1);
                AtomicReference<Throwable> threadFailure = new AtomicReference<>();
                var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                    context.runtime().startActorTask(
                            new OresScheduler.CleanupAwareTask<Void>() {
                                @Override public OresScheduler.Step<Void> resume(
                                        OresScheduler.Resume resume) {
                                    assertTrue(ActorRuntime.inActorExecution(),
                                            "no foreign producer/cancel thread may run guest code");
                                    int frame = segments.incrementAndGet();
                                    if (frame == 1) {
                                        assertTrue(resume.initial());
                                        awaiting.countDown();
                                        return OresScheduler.await(awaited);
                                    }
                                    if (resume.failure() != null) {
                                        assertInstanceOf(
                                                CancellationException.class, resume.failure());
                                        cleanupRuns.incrementAndGet();
                                        assertTrue(OresScheduler.currentTaskInCleanup(),
                                                "cancelled cleanup needs injected CONTROL authority");
                                    }
                                    assertEquals(2, frame, "terminal continuation replayed");
                                    return OresScheduler.done(null);
                                }
                            });
                });
                ref.send(1);
                assertTrue(awaiting.await(5, TimeUnit.SECONDS),
                        "actor did not reach await on attempt " + attempt);
                Thread completer = Thread.ofPlatform().start(() -> {
                    try {
                        race.await();
                        producer.get().resolve(17);
                    } catch (Throwable failure) {
                        threadFailure.compareAndSet(null, failure);
                    }
                });
                Thread stopper = Thread.ofPlatform().start(() -> {
                    try {
                        race.await();
                        ref.stop();
                    } catch (Throwable failure) {
                        threadFailure.compareAndSet(null, failure);
                    }
                });
                race.countDown();
                completer.join();
                stopper.join();
                if (threadFailure.get() != null) {
                    fail("race producer/STOP failed on attempt " + attempt,
                            threadFailure.get());
                }
                assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS),
                        "actor never retired after STOP on attempt " + attempt);
                assertTrue(ref.failure().isEmpty(),
                        "normal structured STOP must not poison supervision");
                assertTrue(segments.get() <= 2,
                        "a dead actor executed duplicate source frames");
                assertTrue(cleanupRuns.get() <= 1,
                        "terminal cleanup must run at most once");
                assertTrue(segments.get() <= 2,
                        "a completed STOP must not re-enter a retired actor");
            }
        }
    }
}
