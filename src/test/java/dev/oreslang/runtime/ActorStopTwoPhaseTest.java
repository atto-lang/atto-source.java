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

    @Test
    void releaseFailureDoesNotStrandOtherQueuedTransportReservations()
            throws Exception {
        var entered = new CountDownLatch(1);
        var releaseTurn = new CountDownLatch(1);
        var secondReleased = new AtomicInteger();
        var attemptedStop = new AtomicReference<Throwable>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, ctx) -> {
                if ("hold".equals(message)) {
                    entered.countDown();
                    if (!releaseTurn.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test failed to release held actor turn");
                    }
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("hold");
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            // Inject transport-release faults without exposing a guest-visible
            // ability to forge release hooks. STOP must still refund both
            // admitted NORMAL credits and execute the second release hook.
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(actor.id());
            var reserve = cell.getClass().getDeclaredMethod("reserveMailboxSlot");
            reserve.setAccessible(true);
            var mailboxField = cell.getClass().getDeclaredField("mailbox");
            mailboxField.setAccessible(true);
            @SuppressWarnings("rawtypes")
            var mailbox = (ChannelRuntime.Channel) mailboxField.get(cell);
            var envelopeType = Class.forName("dev.oreslang.runtime.ActorRuntime$MessageEnvelope");
            var constructor = envelopeType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            assertTrue((boolean) reserve.invoke(cell));
            assertTrue((boolean) reserve.invoke(cell));
            Object first = constructor.newInstance(
                    "first", (Runnable) () -> { throw new IllegalStateException("release fault"); },
                    null, null, 2L, false);
            Object second = constructor.newInstance(
                    "second", (Runnable) secondReleased::incrementAndGet,
                    null, null, 3L, false);
            assertTrue(mailbox.tryWrite(first));
            assertTrue(mailbox.tryWrite(second));

            Thread stopper = Thread.startVirtualThread(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    attemptedStop.set(failure);
                }
            });
            try {
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS)
                                || !stopper.isAlive(),
                        "STOP must make progress despite a release fault");
            } finally {
                releaseTurn.countDown();
                stopper.join(5_000);
            }
            assertFalse(stopper.isAlive(), "STOP must not hang after release failure");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, secondReleased.get(),
                    "a failed release must not strand later mailbox reservations");
            assertTrue(actor.done().isDone());
            assertTrue(actor.failure().isPresent(),
                    "cleanup fault must be retained as the actor's terminal failure");
        }
    }
}
