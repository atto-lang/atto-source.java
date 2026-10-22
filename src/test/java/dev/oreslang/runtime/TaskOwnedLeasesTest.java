package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Task-owned logical leases may retire across OS threads, but must never
 * release before the Task has PHYSICALLY left its scheduler turn.
 */
public class TaskOwnedLeasesTest {
    @Test void cancellationOfSuspendedTaskAutomaticallyReleasesLogicalLease()
            throws Exception {
        OresFuture<Integer> never = new OresFuture<>();
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(new OresScheduler.Task<>() {
                private boolean started;
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    if (!started) {
                        started = true;
                        TaskOwnedLeases.register(released::incrementAndGet);
                        acquired.countDown();
                        return OresScheduler.await(never);
                    }
                    return OresScheduler.done(22);
                }
            });
            assertTrue(acquired.await(3, TimeUnit.SECONDS));
            assertEquals(0, released.get());
            assertTrue(task.cancel("cancel while awaited"));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(1, released.get());
            assertEquals(List.of(), task.cleanupFailures().get(3, TimeUnit.SECONDS));
            assertFalse(never.isCancelled(), "borrowed Future remains live");
        }
    }

    @Test void taskFailureRunsLeaseReleaseAndRetainsOriginalError() throws Exception {
        IllegalArgumentException sourceError = new IllegalArgumentException("broken");
        AtomicInteger released = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                TaskOwnedLeases.register(released::incrementAndGet);
                throw sourceError;
            });
            assertSame(sourceError, assertThrows(ExecutionException.class,
                    () -> task.result().get(3, TimeUnit.SECONDS)).getCause());
            TaskHandle.Failed<?> outcome = assertInstanceOf(TaskHandle.Failed.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertSame(sourceError, outcome.failure());
            assertEquals(1, released.get());
        }
    }

    @Test void cleanupRunsInReverseOrderAndExactlyOnceOnSuccess() throws Exception {
        List<Integer> released = java.util.Collections.synchronizedList(new ArrayList<>());
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                TaskOwnedLeases.register(() -> released.add(1));
                TaskOwnedLeases.register(() -> released.add(2));
                TaskOwnedLeases.register(() -> released.add(3));
                return OresScheduler.done(99);
            });
            assertEquals(Integer.valueOf(99), task.result().get(3, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Success.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(List.of(3, 2, 1), released);
        }
    }

    @Test void explicitEarlyClosePreventsDuplicateAutomaticRelease() throws Exception {
        AtomicInteger released = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                TaskOwnedLeases.Lease resource =
                        TaskOwnedLeases.register(released::incrementAndGet);
                resource.close();
                resource.close();
                return OresScheduler.done(11);
            });
            assertInstanceOf(TaskHandle.Success.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(1, released.get());
        }
    }

    @Test void unrelatedThreadCannotReleaseTaskOwnedLease() throws Exception {
        AtomicReference<TaskOwnedLeases.Lease> lease = new AtomicReference<>();
        AtomicInteger released = new AtomicInteger();
        CountDownLatch registered = new CountDownLatch(1);
        OresFuture<Integer> never = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                lease.set(TaskOwnedLeases.register(released::incrementAndGet));
                registered.countDown();
                return OresScheduler.await(never);
            });
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> lease.get().close());
            assertEquals(0, released.get());
            assertTrue(task.cancel());
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(1, released.get());
        }
    }

    @Test void physicalRunningTurnHoldsLeaseUntilItExits() throws Exception {
        CountDownLatch inTurn = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                TaskOwnedLeases.register(released::incrementAndGet);
                inTurn.countDown();
                // Host-only test fixture simulates an uncooperative native
                // continuation. Guest source code must not block carriers.
                if (!allowExit.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timed out");
                }
                return OresScheduler.done(1);
            });
            try {
                assertTrue(inTurn.await(3, TimeUnit.SECONDS));
                assertTrue(task.cancel("while in critical section"));
                assertEquals(0, released.get(),
                        "must not unlock state used by an active guest turn");
                assertFalse(task.getDone().isDone());
            } finally {
                allowExit.countDown();
            }
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            assertEquals(1, released.get());
        }
    }

    @Test void failedLeaseReleaseIsReportedAndNeverClaimsCleanSuccess()
            throws Exception {
        IllegalStateException releaseFailure =
                new IllegalStateException("logical unlock refused");
        AtomicInteger releasedSibling = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                TaskOwnedLeases.register(releasedSibling::incrementAndGet);
                TaskOwnedLeases.register(() -> { throw releaseFailure; });
                return OresScheduler.done(2);
            });
            assertEquals(Integer.valueOf(2), task.result().get(3, TimeUnit.SECONDS));
            TaskHandle.Failed<?> terminal = assertInstanceOf(
                    TaskHandle.Failed.class, task.getDone().get(3, TimeUnit.SECONDS));
            TaskOwnedLeases.CleanupFailure failure = assertInstanceOf(
                    TaskOwnedLeases.CleanupFailure.class, terminal.failure());
            assertSame(releaseFailure, failure.failures().get(0));
            assertEquals(List.of(releaseFailure),
                    task.cleanupFailures().get(3, TimeUnit.SECONDS));
            assertEquals(1, releasedSibling.get(),
                    "failure must not skip other lease releases");
        }
    }

    @Test void outsideTrackedTaskCannotRegisterLease() {
        assertThrows(IllegalStateException.class,
                () -> TaskOwnedLeases.register(() -> { }));
    }

    @Test void cleanupObservationCannotBeCancelled() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> never = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                TaskOwnedLeases.register(() -> { });
                entered.countDown();
                return OresScheduler.await(never);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(task.cleanupFailures().cancel(false));
            assertFalse(task.getDone().cancel(false));
            task.cancel();
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(List.of(), task.cleanupFailures().get(3, TimeUnit.SECONDS));
        }
    }
}
