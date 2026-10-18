package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime integration: the Future->continuation publication window may not
 * falsely satisfy a supervisor's actor scheduler-drained observation.
 * This does not establish external FFI/timer/resource quiescence.
 */
@Timeout(20)
final class ActorHotUpgradeFuturePublicationTest {
    @Test
    void futureCompletionAndActorContinuationMustDrainBeforeUpgrade() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        OresFuture<Integer> pending = new OresFuture<>();

        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        if (message == 1) {
                            runtime.enqueueOnCompletion(
                                    pending,
                                    runtime.captureCurrentContinuationTarget(),
                                    (value, failure) -> {
                                        try {
                                            if (failure != null) throw new AssertionError(failure);
                                            if (value != 27) throw new AssertionError("wrong continuation value");
                                            if (!runtime.currentThreadOwnsActorExecutionLease())
                                                throw new AssertionError("continuation ran off actor carrier");
                                            invocations.incrementAndGet();
                                        } catch (Throwable unexpected) {
                                            callbackFailure.set(unexpected);
                                        } finally {
                                            invoked.countDown();
                                        }
                                    });
                            registered.countDown();
                        } else if (message == 99) {
                            context.self().stop();
                        }
                    });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send(1);
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                assertFalse(freeze.schedulerDrained(),
                        "pending asynchronous ownership must prevent premature upgrade");
                assertThrows(ActorRuntime.ActorSealedException.class, () -> actor.send(2));
                assertTrue(pending.completeFromRuntime(27));
                assertTrue(invoked.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!freeze.schedulerDrained() && System.nanoTime() < deadline)
                    Thread.sleep(2);
                assertTrue(freeze.schedulerDrained(),
                        "completion reservation and queued continuation must drain");
                assertEquals(1, invocations.get());
                assertNull(callbackFailure.get());
            }
            actor.send(99);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
