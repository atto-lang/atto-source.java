package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Controlled scheduler interleavings for pending Future dependency accounting.
 *
 * <p>Coordination uses latches rather than sleeps or assumptions about carrier
 * scheduling order. Timeout bounds are test deadlock guards, not race timing.</p>
 */
final class NestedFutureCycleInterleavingTest {
    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void simultaneousCrossSchedulerCycleRegistrationFailsWithoutRetainedEdges()
            throws Exception {
        CountDownLatch bothInsideFirstTurn = new CountDownLatch(2);
        CountDownLatch releaseBoth = new CountDownLatch(1);
        AtomicReferenceArray<OresFuture<Integer>> owners = new AtomicReferenceArray<>(2);

        try (OresScheduler first = new OresScheduler(1);
             OresScheduler second = new OresScheduler(1)) {
            OresScheduler[] schedulers = { first, second };

            for (int i = 0; i < schedulers.length; i++) {
                final int index = i;
                owners.set(i, schedulers[i].start(resume -> {
                    if (!resume.initial()) {
                        if (resume.failure() != null) {
                            throw new IllegalStateException(
                                    "cyclic upstream dependency failed", resume.failure());
                        }
                        return OresScheduler.done((Integer) resume.value());
                    }
                    bothInsideFirstTurn.countDown();
                    if (!releaseBoth.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release first turns");
                    }
                    return OresScheduler.await(owners.get(1 - index));
                }));
            }

            assertTrue(bothInsideFirstTurn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "both scheduler turns must reach the barrier");
            releaseBoth.countDown();

            ExecutionException a = assertThrows(ExecutionException.class,
                    () -> owners.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            ExecutionException b = assertThrows(ExecutionException.class,
                    () -> owners.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(containsCycleDiagnostic(a) || containsCycleDiagnostic(b),
                    "one of the tasks must detect the actual dependency cycle");
            for (int i = 0; i < 2; i++) {
                assertFalse(OresScheduler.hasAwaitDependency(owners.get(i)),
                        "terminal tasks cannot retain global await graph edges");
                assertEquals(0, owners.get(i).pendingRuntimeWaiterCount(),
                        "terminal tasks cannot retain runtime observers");
            }
        } finally {
            releaseBoth.countDown();
        }
    }

    @Test
    void cancellationWhileGuestTurnRunsCannotInstallLateAwaitEdge()
            throws Exception {
        CountDownLatch insideTurn = new CountDownLatch(1);
        CountDownLatch releaseTurn = new CountDownLatch(1);
        OresFuture<Integer> pendingProducer = new OresFuture<>();

        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(resume -> {
                insideTurn.countDown();
                if (!releaseTurn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release guest turn");
                }
                return OresScheduler.await(pendingProducer);
            });

            assertTrue(insideTurn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "cancellation must occur while the task owns a guest turn");
            assertTrue(task.cancel(false));
            assertTrue(task.isCancelled());
            releaseTurn.countDown();

            // Closing the owned scheduler waits for its carrier to unwind,
            // so these checks do not depend on arbitrary sleep intervals.
            scheduler.close();
            assertFalse(OresScheduler.hasAwaitDependency(task));
            assertEquals(0, pendingProducer.pendingRuntimeWaiterCount());
            assertFalse(pendingProducer.isCancelled(),
                    "cancelling a consumer cannot cancel an aliased producer");
        } finally {
            releaseTurn.countDown();
        }
    }

    private static boolean containsCycleDiagnostic(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (String.valueOf(cursor.getMessage()).contains(
                    "cyclic Future await dependency detected")) {
                return true;
            }
        }
        return false;
    }
}
