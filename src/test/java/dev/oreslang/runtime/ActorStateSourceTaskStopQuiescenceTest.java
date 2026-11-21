package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual ActorRuntime + actor-owned SourceTask scheduler + RW-region gate.
 * The region ticket is test-injected, not a source-language capability, so
 * this proves physical STOP conjunction rather than compiler authority.
 */
@Timeout(40)
final class ActorStateSourceTaskStopQuiescenceTest {

    @Test
    void cancellationJoinsSourceCleanupAndStateLoanBeforePrivateArenaCloses()
            throws Exception {
        assertCancellationWaitsForBothPhysicalOwners(true);
    }

    @Test
    void cancellationRetainsArenaWhenSourceCleanupFinishesBeforeStateLoan()
            throws Exception {
        assertCancellationWaitsForBothPhysicalOwners(false);
    }

    private void assertCancellationWaitsForBothPhysicalOwners(boolean releaseLoanFirst)
            throws Exception {
        CountDownLatch firstAwait = new CountDownLatch(1);
        CountDownLatch cleanupAwait = new CountDownLatch(1);
        CountDownLatch cleanupDone = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicInteger phases = new AtomicInteger();
        OresFuture<Void> unresolvedUserIo = new OresFuture<>();
        OresFuture<Void> cleanupIo = new OresFuture<>();

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<Integer>spawnPrivate(
                    () -> (message, context) -> {
                        try {
                            OresScheduler.CleanupAwareTask<Void> source = resume -> {
                                int step = phases.getAndIncrement();
                                if (step == 0) {
                                    assertTrue(resume.initial());
                                    firstAwait.countDown();
                                    return OresScheduler.await(unresolvedUserIo);
                                }
                                if (step == 1) {
                                    assertFalse(resume.initial());
                                    assertInstanceOf(
                                            java.util.concurrent.CancellationException.class,
                                            resume.failure(),
                                            "actor STOP must deliver cancellation to lexical cleanup");
                                    cleanupAwait.countDown();
                                    return OresScheduler.await(cleanupIo);
                                }
                                if (step == 2) {
                                    assertFalse(resume.initial());
                                    assertNull(resume.failure());
                                    cleanupDone.countDown();
                                    return OresScheduler.done(null);
                                }
                                throw new AssertionError("duplicate SourceTask resume " + step);
                            };
                            context.runtime().startActorTask(source);
                        } catch (RuntimeException | Error failure) {
                            callbackFailure.set(failure);
                            throw failure;
                        } finally {
                            started.countDown();
                        }
                    });

            actor.ready().get(10, TimeUnit.SECONDS);
            Field actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<ActorRuntime.ActorId, ?> cells =
                    (Map<ActorRuntime.ActorId, ?>) actorsField.get(runtime);
            Object cell = cells.get(actor.id());
            assertNotNull(cell);
            Field regionField = cell.getClass().getDeclaredField("stateRegion");
            regionField.setAccessible(true);
            ActorStateRwLoans region = (ActorStateRwLoans) regionField.get(cell);
            Field arenaField = cell.getClass().getDeclaredField("memorySlice");
            arenaField.setAccessible(true);
            ActorRuntime.ActorMemorySlice arena =
                    (ActorRuntime.ActorMemorySlice) arenaField.get(cell);
            assertNotNull(arena);
            assertFalse(arena.closed());

            ExecutionContext pinned = ExecutionContext.actorBound(
                    739, 1, 1, actor.generation(),
                    739, actor.generation(), actor.generation(), 0, 0);
            ActorStateRwLoans.StateLoan loan =
                    region.acquireReadAsync(pinned).get(5, TimeUnit.SECONDS);
            try {
                actor.send(1);
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertTrue(firstAwait.await(10, TimeUnit.SECONDS),
                        "actor source task must enter real await before cancellation");

                assertTrue(actor.cancel());
                assertTrue(cleanupAwait.await(10, TimeUnit.SECONDS),
                        "cancellation did not reach owning SourceTask's saved cleanup");
                assertFalse(arena.closed());
                assertFalse(actor.done().isDone());
                assertFalse(region.physicallyRetired());

                if (releaseLoanFirst) {
                    // Physical state-loan retirement alone is not enough:
                    // the source Task is still suspended in cancellation cleanup.
                    loan.close();
                    assertTrue(region.physicallyRetired());
                    assertFalse(arena.closed(),
                            "arena freed after state loan despite active cleanup SourceTask");
                    assertFalse(actor.done().isDone());
                } else {
                    // Reverse release order: cleanup resumes while the
                    // generation-fenced state loan still pins the actor region.
                    cleanupIo.completeFromRuntime(null);
                    assertTrue(cleanupDone.await(10, TimeUnit.SECONDS),
                            "source cleanup did not resume before state loan release");
                    assertFalse(region.physicallyRetired(),
                            "state region retired with outstanding physical loan");
                    assertFalse(arena.closed(),
                            "arena freed after source cleanup despite active state loan");
                    assertFalse(actor.done().isDone(),
                            "actor DONE published while state loan was outstanding");
                    loan.close();
                    assertTrue(region.physicallyRetired());
                }
            } finally {
                loan.close();
                cleanupIo.completeFromRuntime(null);
            }

            assertTrue(cleanupDone.await(10, TimeUnit.SECONDS),
                    "source cleanup Future did not resume on actor scheduler");
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS),
                    "actor did not finalize after both physical owners retired");
            assertNull(callbackFailure.get(), () ->
                    "actor handler failed: " + callbackFailure.get());
            assertEquals(3, phases.get());
            assertTrue(arena.closed());
        }
    }
}
