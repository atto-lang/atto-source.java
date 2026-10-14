package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression: STOP detaches envelopes under the actor monitor but runs
 * transport release, exit hooks, and finalization callbacks outside it.
 */
@Timeout(20)
final class ActorStopTwoPhaseTest {
    @Test
    void exitHookCanReenterActorLifecycleFromAnotherThreadWithoutDeadlock()
            throws Exception {
        var actorRef = new AtomicReference<ActorRuntime.ActorRef<String>>();
        var hookEntered = new CountDownLatch(1);
        var nestedDone = new CountDownLatch(1);
        var nestedSucceeded = new AtomicBoolean();
        var hooks = new AtomicInteger();
        try (var runtime = new ActorRuntime()) {
            runtime.setActorExitHook(ignored -> {
                hooks.incrementAndGet();
                hookEntered.countDown();
                // drain() takes the target lifecycle monitor even for a STOP
                // actor. An exit callback holding that monitor would deadlock
                // waiting for this foreign thread.
                Thread nested = Thread.startVirtualThread(() -> {
                    try {
                        nestedSucceeded.set(actorRef.get().drain().isDone());
                    } finally {
                        nestedDone.countDown();
                    }
                });
                try {
                    if (!nestedDone.await(2, TimeUnit.SECONDS))
                        throw new AssertionError("exit hook retained actor lifecycle monitor");
                    nested.join(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            var actor = runtime.<String>spawnShared(() -> (message, ctx) -> { });
            actorRef.set(actor);
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.stop();
            assertTrue(hookEntered.await(5, TimeUnit.SECONDS));
            assertTrue(nestedDone.await(5, TimeUnit.SECONDS));
            assertTrue(nestedSucceeded.get(), "a foreign thread must reenter the stopped actor");
            assertEquals(1, hooks.get(), "external cleanup must run once");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void completionDoesNotPublishWhileExitHookIsStillRunning()
            throws Exception {
        var hookEntered = new CountDownLatch(1);
        var releaseHook = new CountDownLatch(1);
        var terminated = new AtomicBoolean();
        var callerFailed = new AtomicReference<Throwable>();
        try (var runtime = new ActorRuntime()) {
            runtime.setActorExitHook(ignored -> {
                hookEntered.countDown();
                try {
                    if (!releaseHook.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("test did not release exit hook");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            var actor = runtime.<String>spawnShared(() -> (message, ctx) -> { });
            actor.ready().get(5, TimeUnit.SECONDS);
            Thread canceller = Thread.startVirtualThread(() -> {
                try {
                    terminated.set(actor.cancel());
                } catch (Throwable failure) {
                    callerFailed.set(failure);
                }
            });
            try {
                assertTrue(hookEntered.await(5, TimeUnit.SECONDS));
                assertFalse(actor.done().isDone(), "done cannot precede exit cleanup");
                assertFalse(actor.awaitTermination(0, TimeUnit.MILLISECONDS),
                        "termination must not be visible before exit cleanup");
            } finally {
                releaseHook.countDown();
                canceller.join(5_000);
            }
            assertFalse(canceller.isAlive());
            assertTrue(terminated.get(), () -> "cancellation failed: " + callerFailed.get());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.done().isDone());
        }
    }
}
