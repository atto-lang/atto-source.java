package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Real OresMutex guards belong to logical Tasks, not the carrier Thread or
 * the Task which originally constructed the mutex. All getDone assertions
 * require scheduler-confirmed physical retirement before lock reclamation.
 */
public class TaskOwnedMutexRetirementTest {
    @Test
    void cancelledSuspendedTaskAutomaticallyPoisonReleasesSharedGuard() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        var never = new OresFuture<Integer>();
        var acquired = new CountDownLatch(1);
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                var guard = shared.tryLock().orElseThrow();
                guard.value()[0] = 42;
                acquired.countDown();
                return OresScheduler.await(never);
            });
            assertTrue(acquired.await(3, TimeUnit.SECONDS));
            assertFalse(shared.isPoisoned());
            assertTrue(task.cancel("owner cancelled"));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            assertTrue(shared.isPoisoned(), "abandoned mutable state is poisoned");
            assertThrows(OresMutex.PoisonedMutexException.class, shared::tryLock);
            assertTrue(task.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty());
            assertFalse(never.isCancelled(), "borrowed producer is not task-owned");
            shared.recover(state -> { state[0] = 0; return null; });
            assertFalse(shared.isPoisoned());
            var next = shared.tryLock().orElseThrow();
            next.release();
        }
    }

    @Test
    void explicitGuardReleaseDisarmsHiddenCleanupWithoutPoisoning() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                var guard = shared.tryLock().orElseThrow();
                guard.value()[0] = 12;
                guard.release();
                guard.release();
                return OresScheduler.done(3);
            });
            assertInstanceOf(TaskHandle.Success.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertFalse(shared.isPoisoned());
            var verifier = shared.tryLock().orElseThrow();
            assertEquals(12, verifier.value()[0]);
            verifier.release();
        }
    }

    @Test
    void guestFailureKeepsCauseAndPoisonReleasesAbandonedMutex() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        var failure = new IllegalArgumentException("task failed");
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                shared.tryLock().orElseThrow().value()[0] = 99;
                throw failure;
            });
            var outcome = assertInstanceOf(TaskHandle.Failed.class,
                    task.getDone().get(3, TimeUnit.SECONDS));
            assertSame(failure, outcome.failure());
            assertTrue(shared.isPoisoned());
            assertThrows(OresMutex.PoisonedMutexException.class, shared::tryLock);
        }
    }

    @Test
    void taskCancellationRemovesQueuedAsyncWaiterWithoutPoison() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        var originalHolder = shared.lock();
        var pending = new AtomicReference<CompletableFuture<OresMutex.Guard<int[]>>>();
        var queued = new CountDownLatch(1);
        var never = new OresFuture<Integer>();
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                pending.set(shared.lockAsync());
                queued.countDown();
                return OresScheduler.await(never);
            });
            assertTrue(queued.await(3, TimeUnit.SECONDS));
            assertFalse(pending.get().isDone());
            assertTrue(task.cancel("queued acquisition abandoned"));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            assertTrue(pending.get().isCancelled());
            assertFalse(shared.isPoisoned(), "a waiter never mutated shared state");
            originalHolder.release();
            var next = shared.tryLock().orElseThrow();
            next.release();
        } finally {
            if (!originalHolder.released()) originalHolder.release();
        }
    }

    @Test
    void queuedAsyncGrantOnDifferentCarrierRemainsOwnedByRequestingTask()
            throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        var originalHolder = shared.lock();
        var pending = new AtomicReference<CompletableFuture<OresMutex.Guard<int[]>>>();
        var queued = new CountDownLatch(1);
        var never = new OresFuture<Integer>();
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                pending.set(shared.lockAsync());
                queued.countDown();
                return OresScheduler.await(never);
            });
            assertTrue(queued.await(3, TimeUnit.SECONDS));
            assertFalse(pending.get().isDone());
            // The grant runs on THIS host thread, not the Task's carrier.
            originalHolder.release();
            var guard = pending.get().get(3, TimeUnit.SECONDS);
            assertFalse(guard.released());
            assertTrue(task.cancel("owner abandons granted async mutex"));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            assertTrue(guard.released());
            assertTrue(shared.isPoisoned());
            assertTrue(task.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty());
        } finally {
            if (!originalHolder.released()) originalHolder.release();
        }
    }

    @Test
    void actorLocalGateAutoRetiresWithOwningTask() throws Exception {
        var guard = new AtomicReference<OresMutex.Guard<int[]>>();
        var never = new OresFuture<Integer>();
        var acquired = new CountDownLatch(1);
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                var local = OresMutex.local(new int[] { 4 });
                guard.set(local.lock());
                acquired.countDown();
                return OresScheduler.await(never);
            });
            assertTrue(acquired.await(3, TimeUnit.SECONDS));
            assertFalse(guard.get().released());
            assertTrue(task.cancel());
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            assertTrue(guard.get().released());

        }
    }

    @Test
    void frequentEarlyReleasesDoNotExhaustActiveLeaseQuota() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                for (int i = 0; i < TaskOwnedLeases.MAX_LEASES_PER_TASK + 128; i++) {
                    var guard = shared.tryLock().orElseThrow();
                    guard.release();
                }
                return OresScheduler.done(1);
            });
            assertInstanceOf(TaskHandle.Success.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            assertTrue(task.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty());
            assertFalse(shared.isPoisoned());
        }
    }


    @Test
    void equalValuedActorTurnsHaveDistinctLogicalMutexOwnerKeys() {
        record Context(String actor, int generation) { }
        var firstTurn = new Context("actor-1", 7);
        var secondTurn = new Context("actor-1", 7);
        assertEquals(firstTurn, secondTurn, "records are equal by value");
        var firstOwner = OresMutex.Shared.logicalOwnerKey(firstTurn);
        var sameOwner = OresMutex.Shared.logicalOwnerKey(firstTurn);
        var distinctOwner = OresMutex.Shared.logicalOwnerKey(secondTurn);
        assertEquals(firstOwner, sameOwner,
                "one physical/logical execution must preserve its key");
        assertEquals(firstOwner.hashCode(), sameOwner.hashCode());
        assertNotEquals(firstOwner, distinctOwner,
                "two distinct actor turns must never alias by record equality");
    }

    @Test
    void ungrantedLeaseDisarmDoesNotRunAnyReleaseCallback() {
        var releases = new java.util.concurrent.atomic.AtomicInteger();
        var registry = new TaskOwnedLeases.Registry();
        var lease = registry.register(releases::incrementAndGet, releases::incrementAndGet);
        lease.disarmWithoutRelease();
        lease.disarmWithoutRelease();
        assertTrue(registry.releaseOnRetirement().isEmpty());
        assertEquals(0, releases.get());
    }

    @Test
    void differentLogicalTasksOnOneSchedulerCanQueueSameSharedMutex()
            throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        var firstSuspended = new OresFuture<Integer>();
        var secondSuspended = new OresFuture<Integer>();
        var acquired = new CountDownLatch(1);
        var queued = new CountDownLatch(1);
        var pending = new AtomicReference<CompletableFuture<OresMutex.Guard<int[]>>>();
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> first = scheduler.startTracked(resume -> {
                shared.tryLock().orElseThrow().value()[0] = 17;
                acquired.countDown();
                return OresScheduler.await(firstSuspended);
            });
            assertTrue(acquired.await(3, TimeUnit.SECONDS));

            // Both turns use the single scheduler carrier, but the first
            // task's suspended lock must not make the second a recursive owner.
            TaskHandle<Integer> second = scheduler.startTracked(resume -> {
                pending.set(shared.lockAsync());
                queued.countDown();
                return OresScheduler.await(secondSuspended);
            });
            assertTrue(queued.await(3, TimeUnit.SECONDS));
            assertNotNull(pending.get());
            assertFalse(pending.get().isDone(),
                    "a distinct Task must queue rather than be rejected as recursive");
            assertTrue(second.cancel("queued Task cancelled"));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    second.getDone().get(4, TimeUnit.SECONDS));
            assertTrue(pending.get().isCancelled());
            assertFalse(shared.isPoisoned(), "cancelling only a waiter must not poison");
            assertTrue(second.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty());

            assertTrue(first.cancel("holder finished"));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    first.getDone().get(4, TimeUnit.SECONDS));
            assertTrue(shared.isPoisoned());
        }
    }

    @Test
    void timedOutAsyncRequestsDoNotExhaustLiveLeaseQuota() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        var holder = shared.lock();
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                for (int i = 0; i < TaskOwnedLeases.MAX_LEASES_PER_TASK + 256; i++) {
                    var timedOut = shared.lockAsyncFor(Duration.ZERO);
                    assertTrue(timedOut.isCompletedExceptionally(),
                            "zero-duration wait must fail without a grant");
                }
                return OresScheduler.done(1);
            });
            assertInstanceOf(TaskHandle.Success.class,
                    task.getDone().get(5, TimeUnit.SECONDS));
            assertTrue(task.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty(),
                    "failed requests never owned a mutex permit");
            assertFalse(shared.isPoisoned());
        } finally {
            holder.release();
        }
        assertFalse(shared.isPoisoned());
    }

    @Test
    void recursiveAsyncAdmissionsDoNotLeakReservedTaskLeases() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        var holder = shared.lock();
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                var pending = shared.lockAsync();
                assertFalse(pending.isDone());
                for (int i = 0; i < TaskOwnedLeases.MAX_LEASES_PER_TASK + 256; i++) {
                    assertThrows(OresMutex.RecursiveLockException.class,
                            shared::lockAsync);
                }
                assertTrue(pending.cancel(false));
                return OresScheduler.done(1);
            });
            assertInstanceOf(TaskHandle.Success.class,
                    task.getDone().get(5, TimeUnit.SECONDS));
            assertTrue(task.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty());
            assertFalse(shared.isPoisoned());
        } finally {
            holder.release();
        }
    }

    @Test
    void cannotRetireRunningCarrierLockBeforePhysicalQuiescence() throws Exception {
        var shared = OresMutex.shared(new int[] { 0 });
        var entered = new CountDownLatch(1);
        var mayExit = new CountDownLatch(1);
        try (var scheduler = new OresScheduler(2)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                shared.tryLock().orElseThrow().value()[0] = 77;
                entered.countDown();
                if (!mayExit.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test carrier did not exit");
                }
                return OresScheduler.done(0);
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(task.cancel("cancel during protected native turn"));
                assertTrue(shared.tryLock().isEmpty(),
                        "another owner must not enter while old turn is running");
                assertFalse(task.getDone().isDone());
            } finally {
                mayExit.countDown();
            }
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            assertTrue(shared.isPoisoned());
        }
    }
}
