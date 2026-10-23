package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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

    @Test void caughtExplicitReleaseErrorStillBlocksCleanRetirement()
            throws Exception {
        IllegalStateException failedUnlock = new IllegalStateException("not unlocked");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                TaskOwnedLeases.Lease lease =
                        TaskOwnedLeases.register(() -> { throw failedUnlock; });
                // Even if trusted host code catches its own failed early
                // release, physical retirement must retain that diagnostic.
                assertSame(failedUnlock, assertThrows(
                        IllegalStateException.class, lease::close));
                return OresScheduler.done(7);
            });
            assertEquals(Integer.valueOf(7), task.result().get(3, TimeUnit.SECONDS));
            var failed = assertInstanceOf(TaskHandle.Failed.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertInstanceOf(TaskOwnedLeases.CleanupFailure.class, failed.failure());
            assertEquals(List.of(failedUnlock),
                    task.cleanupFailures().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void cancellingOwningScopeRetiresEveryChildLeaseBeforeScopeDone()
            throws Exception {
        CountDownLatch registered = new CountDownLatch(2);
        AtomicInteger released = new AtomicInteger();
        OresFuture<Integer> waiting = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(2);
             TaskScope scope = scheduler.newTaskScope()) {
            for (int i = 0; i < 2; i++) {
                scope.startTask(resume -> {
                    TaskOwnedLeases.register(released::incrementAndGet);
                    registered.countDown();
                    return OresScheduler.await(waiting);
                });
            }
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            assertEquals(0, released.get());
            assertTrue(scope.cancel("shutdown all owned children"));
            assertNull(scope.getDone().get(4, TimeUnit.SECONDS));
            assertEquals(2, released.get());
            assertFalse(waiting.isCancelled(),
                    "borrowed producer is independent of cancelled TaskScope");
        }
    }

    @Test void repeatedEarlyUnlocksReuseLiveLeaseCapacity() throws Exception {
        AtomicInteger releases = new AtomicInteger();
        int loops = TaskOwnedLeases.MAX_LEASES_PER_TASK + 3072;
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = scheduler.startTracked(
                    new OresScheduler.Task<Integer>() {
                private int cursor;
                @Override public OresScheduler.Step<Integer> resume(
                        OresScheduler.Resume resume) {
                    int limit = Math.min(loops, cursor + 64);
                    while (cursor < limit) {
                        TaskOwnedLeases.Lease lease =
                                TaskOwnedLeases.register(releases::incrementAndGet);
                        lease.close();
                        cursor++;
                    }
                    return cursor == loops
                            ? OresScheduler.done(loops)
                            : OresScheduler.cooperate();
                }
            });
            assertEquals(Integer.valueOf(loops),
                    handle.result().get(5, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Success.class,
                    handle.getDone().get(5, TimeUnit.SECONDS));
            assertEquals(loops, releases.get());
            assertEquals(List.of(), handle.cleanupFailures().get(5, TimeUnit.SECONDS));
        }
    }

    @Test void outstandingLiveLeaseQuotaCannotBeBypassed() throws Exception {
        AtomicInteger released = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = scheduler.startTracked(
                    new OresScheduler.Task<Integer>() {
                private int count;
                @Override public OresScheduler.Step<Integer> resume(
                        OresScheduler.Resume resume) {
                    int limit = Math.min(
                            TaskOwnedLeases.MAX_LEASES_PER_TASK, count + 64);
                    while (count < limit) {
                        TaskOwnedLeases.register(released::incrementAndGet);
                        count++;
                    }
                    if (count < TaskOwnedLeases.MAX_LEASES_PER_TASK) {
                        return OresScheduler.cooperate();
                    }
                    assertThrows(IllegalStateException.class,
                            () -> TaskOwnedLeases.register(released::incrementAndGet));
                    return OresScheduler.done(1);
                }
            });
            assertInstanceOf(TaskHandle.Success.class,
                    handle.getDone().get(5, TimeUnit.SECONDS));
            assertEquals(TaskOwnedLeases.MAX_LEASES_PER_TASK, released.get());
        }
    }

    @Test void cleanupFailurePoisonsOwningScopeEvenAfterChildPhysicallyRetires()
            throws Exception {
        IllegalStateException leakedLock = new IllegalStateException("logical release failed");
        try (OresScheduler scheduler = new OresScheduler(2);
             TaskScope scope = scheduler.newTaskScope()) {
            TaskHandle<Integer> child = scope.startTask(resume -> {
                TaskOwnedLeases.register(() -> { throw leakedLock; });
                return OresScheduler.done(14);
            });
            scope.seal();
            var failure = assertThrows(ExecutionException.class,
                    () -> scope.getDone().get(4, TimeUnit.SECONDS));
            var cleanup = assertInstanceOf(
                    TaskOwnedLeases.CleanupFailure.class, failure.getCause());
            assertSame(leakedLock, cleanup.failures().get(0));
            assertEquals(List.of(leakedLock),
                    scope.cleanupFailures().get(4, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Failed.class,
                    child.getDone().get(4, TimeUnit.SECONDS));
            assertFalse(scope.getDone().isCancelled());
        }
    }

    @Test void healthyScopeAfterSeveralChildReleasesRemainsClean() throws Exception {
        AtomicInteger released = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(2);
             TaskScope scope = scheduler.newTaskScope()) {
            for (int i = 0; i < 4; i++) {
                scope.startTask(resume -> {
                    TaskOwnedLeases.register(released::incrementAndGet);
                    return OresScheduler.done(1);
                });
            }
            scope.seal();
            assertNull(scope.getDone().get(4, TimeUnit.SECONDS));
            assertEquals(List.of(), scope.cleanupFailures().get(4, TimeUnit.SECONDS));
            assertEquals(4, released.get());
        }
    }

    @Test void failedUnlockDoesNotEraseOriginalThrownTaskError()
            throws Exception {
        IllegalArgumentException original = new IllegalArgumentException("source throw");
        IllegalStateException unlock = new IllegalStateException("unlock failed");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                TaskOwnedLeases.register(() -> { throw unlock; });
                throw original;
            });
            assertSame(original, assertThrows(ExecutionException.class,
                    () -> task.result().get(4, TimeUnit.SECONDS)).getCause());
            var physical = assertInstanceOf(TaskHandle.Failed.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            var cleanup = assertInstanceOf(
                    TaskOwnedLeases.CleanupFailure.class, physical.failure());
            assertSame(unlock, cleanup.failures().get(0));
            assertSame(original, cleanup.getCause());
            var primary = assertInstanceOf(
                    TaskHandle.Failed.class, cleanup.originalOutcome());
            assertSame(original, primary.failure());
            assertEquals(List.of(unlock),
                    task.cleanupFailures().get(4, TimeUnit.SECONDS));
        }
    }

    @Test void failedUnlockRetainsAuthenticTaskCancellationReason()
            throws Exception {
        IllegalStateException unlock = new IllegalStateException("unlock failed");
        CountDownLatch acquired = new CountDownLatch(1);
        OresFuture<Integer> pending = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                TaskOwnedLeases.register(() -> { throw unlock; });
                acquired.countDown();
                return OresScheduler.await(pending);
            });
            assertTrue(acquired.await(3, TimeUnit.SECONDS));
            assertTrue(task.cancel("owner revoked"));
            assertTrue(task.result().isCancelled());
            var physical = assertInstanceOf(TaskHandle.Failed.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            var cleanup = assertInstanceOf(
                    TaskOwnedLeases.CleanupFailure.class, physical.failure());
            var original = assertInstanceOf(
                    TaskHandle.Cancelled.class, cleanup.originalOutcome());
            assertEquals("owner revoked", original.reason());
            assertSame(unlock, cleanup.failures().get(0));
            assertFalse(pending.isCancelled());
        }
    }
    @Test void leaseRegistryJoinsClaimedHandoffAndPreservesRepeatedObservers()
            throws Exception {
        var registry = new TaskOwnedLeases.Registry();
        var physicalReceipt = new CompletableFuture<Void>();
        registry.trackPhysicalSettlement(physicalReceipt);
        var first = registry.releaseOnRetirementAsync();
        var second = registry.releaseOnRetirementAsync();
        assertSame(first, second, "repeated retirement cannot forge clean completion");
        assertFalse(first.isDone(), "in-flight handoff has not returned its permit");
        physicalReceipt.complete(null);
        assertEquals(List.of(), first.get(3, TimeUnit.SECONDS));
        assertSame(first, registry.releaseOnRetirementAsync());
    }

    @Test void failedPhysicalReceiptCannotPublishCleanTaskDone() throws Exception {
        var registered = new CountDownLatch(1);
        var receipt = new CompletableFuture<Void>();
        var never = new OresFuture<Integer>();
        var physicalFailure = new IllegalStateException("handoff permit not reclaimed");
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                TaskHandle.registerPhysicalSettlement(receipt);
                registered.countDown();
                return OresScheduler.await(never);
            });
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            assertTrue(task.cancel("test physical receipt failure"));
            assertTrue(task.result().isCancelled());
            assertFalse(task.getDone().isDone());
            receipt.completeExceptionally(physicalFailure);
            var outcome = assertInstanceOf(TaskHandle.Failed.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            var failed = assertInstanceOf(
                    TaskOwnedLeases.CleanupFailure.class, outcome.failure());
            assertSame(physicalFailure, failed.failures().get(0));
            assertEquals(List.of(physicalFailure),
                    task.cleanupFailures().get(3, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Cancelled.class, failed.originalOutcome());
        }
    }

}
