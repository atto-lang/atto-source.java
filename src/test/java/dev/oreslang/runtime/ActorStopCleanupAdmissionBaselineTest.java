package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActorStopCleanupAdmissionBaselineTest {
    @Test
    void stopRejectsAnAuthenticatedButNeverStartedSourceTurn() throws Exception {
        AtomicInteger guestTurns = new AtomicInteger();
        AtomicReference<OresFuture<Integer>> taskRef = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFuture<Integer> task = context.runtime().startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                            guestTurns.incrementAndGet();
                            return OresScheduler.done(19);
                        });
                taskRef.set(context.runtime().ownCurrentActorFuture(task));
                context.self().stop();
                published.countDown();
            });
            actor.send("stop");
            assertTrue(published.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, guestTurns.get(),
                    "a queued initial source turn has no lexical cleanup authority");
            assertThrows(CancellationException.class,
                    () -> taskRef.get().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void stopResumesOnlyAuthenticatedSuspendedCleanupAndRetainsActorUntilItQuiesces()
            throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        OresFuture<Integer> cleanupGate = new OresFuture<>();
        AtomicInteger guestTurns = new AtomicInteger();
        AtomicReference<OresFuture<Integer>> taskRef = new AtomicReference<>();
        CountDownLatch suspended = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if (!"start".equals(message)) {
                    throw new AssertionError("ordinary message after STOP");
                }
                OresFuture<Integer> task = context.runtime().startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) event -> {
                            int turn = guestTurns.incrementAndGet();
                            if (turn == 1) {
                                suspended.countDown();
                                return OresScheduler.await(producer);
                            }
                            if (turn == 2) {
                                assertInstanceOf(CancellationException.class, event.failure());
                                assertTrue(context.runtime().currentThreadOwnsActorExecutionLease());
                                cleanupEntered.countDown();
                                return OresScheduler.await(cleanupGate);
                            }
                            assertEquals(3, turn);
                            assertTrue(context.runtime().currentThreadOwnsActorExecutionLease());
                            return OresScheduler.done(42);
                        });
                taskRef.set(context.runtime().ownCurrentActorFuture(task));
            });
            actor.send("start");
            assertTrue(suspended.await(5, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone(),
                    "actor memory and DONE must remain pending through asynchronous cleanup");
            assertFalse(taskRef.get().isDone(), "source task Future is still cleaning up");
            assertThrows(RuntimeException.class, () -> actor.send("after-stop"));
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertFalse(producer.isCancelled(), "shared producer remains with its owner");
            cleanupGate.completeFromRuntime(8);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(3, guestTurns.get());
            assertThrows(CancellationException.class,
                    () -> taskRef.get().get(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), "normal STOP should not fabricate failure");
        }
    }

    @Test
    void stalledSourceCleanupIsRevokedWithExplicitFailureAndNoFurtherGuestTurn()
            throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        OresFuture<Integer> neverCleanup = new OresFuture<>();
        AtomicInteger turns = new AtomicInteger();
        CountDownLatch suspended = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                context.runtime().ownCurrentActorFuture(context.runtime().startActorTask(
                        (OresScheduler.CleanupAwareTask<Integer>) event -> {
                            int turn = turns.incrementAndGet();
                            if (turn == 1) {
                                suspended.countDown();
                                return OresScheduler.await(producer);
                            }
                            if (turn == 2) {
                                assertInstanceOf(CancellationException.class, event.failure());
                                cleanupEntered.countDown();
                                return OresScheduler.await(neverCleanup);
                            }
                            throw new AssertionError("expired cleanup resumed");
                        }));
            });
            actor.send("start");
            assertTrue(suspended.await(5, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(8, TimeUnit.SECONDS),
                    "bounded STOP must not strand actor resources indefinitely");
            assertEquals(2, turns.get());
            assertTrue(actor.failure().orElseThrow().getMessage()
                            .contains("source cleanup exceeded bounded STOP deadline"),
                    "deadline must not be misreported as successful lexical cleanup");
            neverCleanup.completeFromRuntime(9);
            assertEquals(2, turns.get(), "revoked cleanup cannot resume after termination");
            assertEquals(0, neverCleanup.pendingRuntimeWaiterCount());
        }
    }
}
