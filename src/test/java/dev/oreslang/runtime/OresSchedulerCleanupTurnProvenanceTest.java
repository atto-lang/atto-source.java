package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OresSchedulerCleanupTurnProvenanceTest {
    @Test
    void onlySchedulerMintedLiveCancellationDispatchHasCleanupAuthority() throws Exception {
        ArrayDeque<Runnable> actorLane = new ArrayDeque<>();
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "owner", 1, actorLane::add);
             OresScheduler impostor = OresScheduler.runtimeOwned(
                "other", 1, command -> { throw new AssertionError("no work"); })) {
            OresFuture<Integer> gate = new OresFuture<>();
            OresFuture<Integer> cleanup = new OresFuture<>();
            AtomicInteger turns = new AtomicInteger();
            OresFuture<Integer> result = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>) event -> {
                        int pc = turns.getAndIncrement();
                        if (pc == 0) return OresScheduler.await(gate);
                        if (pc == 1) {
                            assertInstanceOf(CancellationException.class, event.failure());
                            return OresScheduler.await(cleanup);
                        }
                        return OresScheduler.done(99);
                    });

            Runnable initial = actorLane.removeFirst();
            assertInstanceOf(OresScheduler.CleanupTurn.class, initial);
            var initialTurn = (OresScheduler.CleanupTurn) initial;
            assertTrue(initialTurn.ownedBy(scheduler));
            assertFalse(initialTurn.authorizedBy(scheduler));
            assertFalse(initialTurn.authorizedBy(impostor));
            initial.run();
            assertEquals(1, turns.get());

            assertTrue(result.cancel(false));
            Runnable resumed = actorLane.removeFirst();
            assertInstanceOf(OresScheduler.CleanupTurn.class, resumed);
            var cleanupTurn = (OresScheduler.CleanupTurn) resumed;
            assertTrue(cleanupTurn.ownedBy(scheduler));
            assertTrue(cleanupTurn.authorizedBy(scheduler));
            assertFalse(cleanupTurn.authorizedBy(impostor),
                    "ownership is exact scheduler identity, not matching names");
            assertFalse(initialTurn.authorizedBy(impostor));
            resumed.run();
            assertFalse(result.isDone(), "cleanup still awaits the second producer");
            assertEquals(2, turns.get());

            cleanup.completeFromRuntime(1);
            Runnable finish = actorLane.removeFirst();
            assertTrue(((OresScheduler.CleanupTurn) finish).authorizedBy(scheduler));
            finish.run();
            assertThrows(CancellationException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertFalse(cleanupTurn.authorizedBy(scheduler),
                    "stale retained envelope loses authorization after terminal publication");
            assertEquals(3, turns.get());
            assertEquals(0, gate.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void taskCancelledBeforeFirstGuestTurnNeverMintsCleanupAuthorization() throws Exception {
        ArrayDeque<Runnable> actorLane = new ArrayDeque<>();
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "owner", 1, actorLane::add)) {
            AtomicInteger guestTurns = new AtomicInteger();
            OresFuture<Integer> result = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>) event -> {
                        guestTurns.incrementAndGet();
                        return OresScheduler.done(9);
                    });
            var initial = (OresScheduler.CleanupTurn) actorLane.removeFirst();
            assertTrue(result.cancel(false));
            assertFalse(initial.authorizedBy(scheduler));
            initial.run();
            assertThrows(CancellationException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertEquals(0, guestTurns.get());
        }
    }
}
