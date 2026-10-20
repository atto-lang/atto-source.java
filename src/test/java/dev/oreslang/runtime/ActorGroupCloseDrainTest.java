package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorGroupCloseDrainTest {
    // Source actor factories must not capture mutable host objects. The test
    // coordinator is explicit trusted host fixture state, not actor payload.
    private static volatile CountDownLatch signalEntered;
    private static volatile CountDownLatch signalExit;

    @Test
    void closeDoesNotFalselyReportCompletionWhileWorkerTurnRuns() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1);
            signalEntered = entered;
            signalExit = exit;
            ActorRuntime.ActorRef<Integer> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> {
                        ActorGroupCloseDrainTest.signalEntered.countDown();
                        try {
                            ActorGroupCloseDrainTest.signalExit.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
            worker.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> closed;
            try {
                closed = group.closeAsync();
                assertSame(closed, group.closedDone());
                assertFalse(closed.cancel(false),
                        "lifecycle observers must not cancel physical actor shutdown");
                assertFalse(closed.isDone(),
                        "cleared worker registry must not pretend active turn has finalized");
                assertTrue(group.closed());
                assertEquals(0, group.workerCount());
            } finally {
                exit.countDown();
            }
            try {
                closed.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException expectedCancellation) {
                // Structured cancellation is allowed to reject actor.done.
            }
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedCloseAfterSealRetainsExactRecoveryOwnerForRetry() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();

            var faultField = group.getClass().getDeclaredField(
                    "groupCloseAfterSealFaultForTesting");
            var activeField = group.getClass().getDeclaredField("groupCloseActive");
            var completeField = group.getClass().getDeclaredField("groupCloseComplete");
            var ownedField = group.getClass().getDeclaredField(
                    "groupCloseOwnedWorkers");
            faultField.setAccessible(true);
            activeField.setAccessible(true);
            completeField.setAccessible(true);
            ownedField.setAccessible(true);

            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            faultField.set(group, (Runnable) () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new OutOfMemoryError(
                            "injected after ActorGroup admission seal");
                }
            });

            var failure = assertThrows(OutOfMemoryError.class, group::closeAsync);
            assertTrue(failure.getMessage().contains("ActorGroup admission seal"));
            assertTrue(group.closed(),
                    "admission remains revoked after a failed close attempt");
            assertFalse(activeField.getBoolean(group),
                    "failed cleanup must release its exclusive retry claim");
            assertFalse(completeField.getBoolean(group),
                    "the admission seal is not proof of completed cleanup");
            assertNotNull(ownedField.get(group),
                    "retry must retain the exact pre-seal worker snapshot");
            assertFalse(group.closedDone().isDone(),
                    "failed cleanup cannot forge the close completion barrier");

            var groupsField = ActorRuntime.class.getDeclaredField("actorGroups");
            groupsField.setAccessible(true);
            var groups = (java.util.Map<Object, Object>) groupsField.get(runtime);
            assertSame(group, groups.get(group.id()),
                    "failed close must remain discoverable for runtime cleanup");

            OresFuture<Void> done = group.closeAsync();
            assertEquals(2, attempts.get(),
                    "retry must resume the same post-seal cleanup boundary");
            assertSame(done, group.closedDone());
            assertTrue(done.isDone());
            done.get(5, TimeUnit.SECONDS);
            assertTrue(completeField.getBoolean(group));
            assertNull(ownedField.get(group),
                    "successful cleanup must release its durable close owner");
            assertNull(groups.get(group.id()),
                    "successful retry must remove the fully closed group");

            assertSame(done, group.closeAsync(),
                    "completed ActorGroup close remains idempotent");
            assertEquals(2, attempts.get(),
                    "completed close must not replay the fault boundary");
        }
    }

    @Test
    void emptyGroupCloseFinishesAndRemainsIdempotent() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            OresFuture<Void> done = group.closeAsync();
            assertSame(done, group.closedDone());
            assertTrue(done.isDone());
            assertFalse(done.cancel(false));
            done.get(5, TimeUnit.SECONDS);
            assertSame(done, group.closeAsync());
        }
    }
}
