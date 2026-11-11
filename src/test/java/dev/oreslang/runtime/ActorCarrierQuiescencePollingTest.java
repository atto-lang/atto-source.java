package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-backed ActorRuntime TurnExecutor tests, not a source-free model.
 * A processed mailbox alone never certifies the physical carrier has exited.
 */
@Timeout(15)
final class ActorCarrierQuiescencePollingTest {
    private static void eventuallyQuiescent(
            ActorRuntime runtime, ActorRuntime.ActorRef<?> actor) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!runtime.httpCarrierQuiescent(actor)
                && System.nanoTime() - deadline < 0) {
            Thread.sleep(5);
        }
        assertTrue(runtime.httpCarrierQuiescent(actor),
                "physical carrier failed to exit before test deadline");
    }

    @org.junit.jupiter.api.RepeatedTest(10)
    void stoppedStartupCarrierIsNotMistakenForQuiescence() throws Exception {
        var carrierEntered = new CountDownLatch(1);
        var allowStartup = new CountDownLatch(1);
        var first = new AtomicBoolean();
        ActorRuntime.TurnExecutor turnExecutor = task -> {
            if (first.compareAndSet(false, true)) {
                carrierEntered.countDown();
                try {
                    if (!allowStartup.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("startup gate deadline");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            task.run();
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1,1,1,16), turnExecutor)) {
            var actor = runtime.spawnShared(() -> (Integer message, ActorRuntime.ActorContext context) -> { });
            try {
                actor.ready();
                assertTrue(carrierEntered.await(5, TimeUnit.SECONDS));
                assertFalse(runtime.httpCarrierQuiescent(actor),
                        "blocked initializer still holds its physical carrier");
                allowStartup.countDown();
                actor.ready().get(5, TimeUnit.SECONDS);
                eventuallyQuiescent(runtime, actor);
            } finally {
                allowStartup.countDown();
            }
        }
    }

    @org.junit.jupiter.api.RepeatedTest(10)
    void mailboxDrainDoesNotCertifyTurnExecutorExit() throws Exception {
        var processed = new CountDownLatch(1);
        var carrierHeld = new CountDownLatch(1);
        var releaseCarrier = new CountDownLatch(1);
        var first = new AtomicBoolean();
        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (processed.getCount()==0 && first.compareAndSet(false, true)) {
                carrierHeld.countDown();
                try {
                    if (!releaseCarrier.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("physical carrier gate deadline");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1,1,8,16), executor)) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() ->
                    (message, context) -> processed.countDown());
            try {
                actor.send(1);
                assertTrue(processed.await(5, TimeUnit.SECONDS));
                assertTrue(carrierHeld.await(5, TimeUnit.SECONDS));
                assertTrue(actor.drain().isDone(),
                        "test must distinguish mailbox drain from physical leave");
                assertFalse(runtime.httpCarrierQuiescent(actor),
                        "mailbox drained, but TurnExecutor has not returned");
                releaseCarrier.countDown();
                eventuallyQuiescent(runtime, actor);
            } finally {
                releaseCarrier.countDown();
            }
        }
    }

    @Test
    void canceledActorRemainsNonquiescentUntilPhysicalStopFinishes() throws Exception {
        var processed = new CountDownLatch(1);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean();
        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (processed.getCount()==0 && first.compareAndSet(false, true)) {
                held.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("cancel carrier gate deadline");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1,1,1,16), executor)) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() ->
                    (message, context) -> processed.countDown());
            try {
                actor.send(1);
                assertTrue(held.await(5, TimeUnit.SECONDS));
                actor.cancel();
                assertFalse(runtime.httpCarrierQuiescent(actor),
                        "logical cancellation cannot retire the physical carrier");
                release.countDown();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                eventuallyQuiescent(runtime, actor);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void cannotObserveForeignRuntimeActor() throws Exception {
        try (ActorRuntime first = new ActorRuntime(
                    IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(1,1,1,16), Runnable::run);
             ActorRuntime second = new ActorRuntime(
                    IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(1,1,1,16), Runnable::run)) {
            ActorRuntime.ActorRef<Integer> actor = first.spawnShared(() ->
                    (message, context) -> {});
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> second.httpCarrierQuiescent(actor));
        }
    }
}
