package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;

import org.junit.jupiter.api.Test;

/**
 * A host-visible termination wait is a physical retirement barrier, not just
 * the beginning of actor cleanup. In particular, the actor cell can be marked
 * finalized and removed from the registry before its done notification is
 * published. Neither event alone may satisfy awaitTermination().
 */
@Timeout(20)
final class ActorTerminationRetirementBarrierTest {

    @Test
    void awaitTerminationWaitsForDoneAndMessageAccountingRetirement() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            // Repeated short-lived actors expose the deregistration versus
            // terminal-notification race without sleeps or scheduler parking.
            for (int iteration = 0; iteration < 32; iteration++) {
                ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                        () -> (message, context) -> { });
                actor.send(iteration);
                actor.stop();

                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                        "actor retirement deadline exceeded on iteration " + iteration);
                assertTrue(actor.done().isDone(),
                        "awaitTermination returned before get_done settled");
                assertFalse(actor.isAlive(),
                        "awaitTermination returned before actor retirement");
                assertEquals(0L, runtime.sharedMemoryBytes(),
                        "awaitTermination returned before shared message memory was released");
            }
        }
    }

    @Test
    void concurrentHostWaitersSeeOnlyPhysicalRetirementAfterCancellation() throws Exception {
        CountDownLatch turnEntered = new CountDownLatch(1);
        CountDownLatch releaseTurn = new CountDownLatch(1);
        CountDownLatch waitersStarted = new CountDownLatch(8);
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        Thread[] waiters = new Thread[8];

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        turnEntered.countDown();
                        if (!releaseTurn.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("guest turn release was not published");
                        }
                    });

            actor.send(1);
            assertTrue(turnEntered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < waiters.length; i++) {
                waiters[i] = Thread.ofPlatform()
                        .name("actor-retirement-waiter-" + i)
                        .start(() -> {
                            waitersStarted.countDown();
                            try {
                                if (!actor.awaitTermination(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("host waiter missed physical retirement");
                                }
                                if (!actor.done().isDone()) {
                                    throw new AssertionError("host waiter passed before done");
                                }
                            } catch (Throwable failure) {
                                waiterFailure.compareAndSet(null, failure);
                            }
                        });
            }
            assertTrue(waitersStarted.await(5, TimeUnit.SECONDS));
            assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS),
                    "unfinished running turn must not satisfy a zero-timeout wait");
            assertTrue(actor.cancel(), "structured cancellation should seal USER admission");
            assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS),
                    "cancel is not proof of physical turn retirement");

            releaseTurn.countDown();
            for (Thread waiter : waiters) {
                waiter.join(5_000);
                assertFalse(waiter.isAlive(), "host waiter remained blocked after retirement");
            }
            assertNull(waiterFailure.get());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(actor.isAlive());
            assertEquals(0L, runtime.sharedMemoryBytes());
        } finally {
            releaseTurn.countDown();
        }
    }

    @Test
    void failedBehaviorInitializationPublishesTheSamePhysicalRetirementBarrier()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                    () -> { throw new IllegalStateException("actor factory failed"); });

            ExecutionException failedReady = assertThrows(
                    ExecutionException.class,
                    () -> actor.ready().get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failedReady.getCause());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.done().isDone());
            assertFalse(actor.isAlive());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void stopBeforeFirstMessageStillPublishesPhysicalRetirement()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnPrivate(
                    () -> (message, context) -> { });

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.done().isDone());
            assertFalse(actor.isAlive());
        }
    }

    @Test
    void interruptingAHostWaiterDoesNotCancelTheActor()
            throws Exception {
        AtomicReference<Throwable> result = new AtomicReference<>();
        CountDownLatch enteredWait = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                    () -> (message, context) -> { });
            Thread waiter = Thread.ofPlatform()
                    .name("actor-interrupted-retirement-waiter")
                    .start(() -> {
                        enteredWait.countDown();
                        try {
                            actor.awaitTermination(10, TimeUnit.SECONDS);
                            result.set(new AssertionError(
                                    "wait must not succeed before actor STOP"));
                        } catch (Throwable failure) {
                            result.set(failure);
                        }
                    });
            assertTrue(enteredWait.await(5, TimeUnit.SECONDS));
            waiter.interrupt();
            waiter.join(5_000);
            assertFalse(waiter.isAlive());
            assertInstanceOf(InterruptedException.class, result.get());
            assertTrue(actor.isAlive(), "interrupted host waiter must not stop actor");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.done().isDone());
        }
    }
}
