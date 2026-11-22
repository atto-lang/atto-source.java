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
    void onlyCompletedSuccessfulAsyncGrantsCountAsPublishedOwnership() {
        var waiting = new CompletableFuture<Object>();
        assertFalse(OresMutex.Shared.isPublishedAsyncGrant(null));
        assertFalse(OresMutex.Shared.isPublishedAsyncGrant(waiting));
        assertTrue(waiting.cancel(false));
        assertFalse(OresMutex.Shared.isPublishedAsyncGrant(waiting),
                "internally bound but cancelled guards were never published");

        var timedOut = new CompletableFuture<Object>();
        timedOut.completeExceptionally(new OresMutex.LockTimeoutException(
                java.time.Duration.ZERO));
        assertFalse(OresMutex.Shared.isPublishedAsyncGrant(timedOut));

        var succeeded = new CompletableFuture<Object>();
        succeeded.complete(new Object());
        assertTrue(OresMutex.Shared.isPublishedAsyncGrant(succeeded));
        assertFalse(succeeded.cancel(false));
        assertTrue(OresMutex.Shared.isPublishedAsyncGrant(succeeded));
    }

    @Test
    void dequeuedGrantCancellationJoinsPhysicalReclamationBeforeTaskDone() throws Exception {
        var shared = OresMutex.shared(new int[] { 19 });
        // Host guards are owned by the acquiring thread. The holder lives on
        // its own carrier so that carrier's release performs the handoff.
        var originalHolder = new ForeignHolder<>(shared);
        var requestQueued = new CountDownLatch(1);
        var grantDequeued = new CountDownLatch(1);
        var finishGrant = new CountDownLatch(1);
        var pending = new AtomicReference<CompletableFuture<OresMutex.Guard<int[]>>>();
        var never = new OresFuture<Integer>();

        // This hook runs after the queue's dequeue/claim linearization
        // but before markOwner, bindGuard and Future publication.
        shared.setAfterAsyncDequeueForTesting(() -> {
            grantDequeued.countDown();
            try {
                if (!finishGrant.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("dequeued grant did not resume");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                pending.set(shared.lockAsync());
                requestQueued.countDown();
                return OresScheduler.await(never);
            });
            assertTrue(requestQueued.await(3, TimeUnit.SECONDS));
            assertFalse(pending.get().isDone());

            var granting = originalHolder.thread;
            originalHolder.releaseOnOwnerThread();
            try {
                assertTrue(grantDequeued.await(3, TimeUnit.SECONDS),
                        "the waiter must be removed and its handoff claimed");
                var requestTerminal = new CompletableFuture<Void>();
                pending.get().whenComplete((value, failure) ->
                        requestTerminal.complete(null));
                assertTrue(task.cancel("cancel after dequeue before delivery"));
                requestTerminal.get(3, TimeUnit.SECONDS);
                assertTrue(pending.get().isCancelled(),
                        "retirement must cancel before the paused grant publishes");
                assertFalse(task.getDone().isDone(),
                        "getDone must JOIN a claimed grant even after Future cancellation");
                assertFalse(task.cleanupFailures().isDone(),
                        "cleanup receipt must not precede physical permit reclamation");
            } finally {
                finishGrant.countDown();
                granting.join(4000);
                shared.setAfterAsyncDequeueForTesting(null);
            }

            assertFalse(granting.isAlive(), "grant carrier must finish its handoff");
            assertNull(originalHolder.failure.get(), "grant carrier must not throw");
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            assertTrue(task.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty());
            assertFalse(shared.isPoisoned(),
                    "a cancelled never-published guard must not poison the shared state");

            var newOwner = shared.tryLock().orElseThrow();
            assertEquals(19, newOwner.value()[0]);
            // A second logical owner on another host carrier must NOT also
            // receive the single permit while the first guard is live.
            var competing = CompletableFuture.supplyAsync(shared::tryLock)
                    .get(3, TimeUnit.SECONDS);
            if (competing.isPresent()) competing.get().release();
            assertTrue(competing.isEmpty(), "physical permit must have exactly one owner");
            newOwner.release();
            // Second admission proves the original permit was neither leaked
            // nor double-issued while the cancelled request was reclaimed.
            var followingOwner = shared.tryLock().orElseThrow();
            followingOwner.release();
        } finally {
            finishGrant.countDown();
            shared.setAfterAsyncDequeueForTesting(null);
            originalHolder.close();
        }
    }

    @Test
    void publishedGrantRetirementWaitsForGrantCarrierBeforePoisoning() throws Exception {
        var shared = OresMutex.shared(new int[] { 42 });
        var original = new ForeignHolder<>(shared);
        var queued = new CountDownLatch(1);
        var subscriberEntered = new CountDownLatch(1);
        var releaseSubscriber = new CountDownLatch(1);
        var pending = new AtomicReference<CompletableFuture<OresMutex.Guard<int[]>>>();
        var never = new OresFuture<Integer>();

        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                pending.set(shared.lockAsync());
                queued.countDown();
                return OresScheduler.await(never);
            });
            assertTrue(queued.await(3, TimeUnit.SECONDS));
            pending.get().whenComplete((guard, failure) -> {
                if (failure != null) return;
                subscriberEntered.countDown();
                try {
                    if (!releaseSubscriber.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("published observer did not resume");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            var granting = original.thread;
            original.releaseOnOwnerThread();
            try {
                assertTrue(subscriberEntered.await(3, TimeUnit.SECONDS));
                assertTrue(OresMutex.Shared.isPublishedAsyncGrant(pending.get()));
                var delivered = pending.get().getNow(null);
                assertNotNull(delivered);
                assertTrue(task.cancel("published-but-not-yet-settled grant"));
                // No cleanup may revoke the permit while a synchronous
                // publication subscriber still runs on this carrier.
                assertFalse(task.getDone().isDone());
                assertFalse(task.cleanupFailures().isDone());
                assertFalse(delivered.released());
                assertFalse(shared.isPoisoned());
            } finally {
                releaseSubscriber.countDown();
                granting.join(4000);
            }
            assertFalse(granting.isAlive());
            assertNull(original.failure.get());
            assertInstanceOf(TaskHandle.Cancelled.class,
                    task.getDone().get(4, TimeUnit.SECONDS));
            assertTrue(task.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty());
            assertTrue(shared.isPoisoned(), "published abandoned guard must poison");
            assertTrue(pending.get().getNow(null).released());
            shared.recover(value -> { value[0] = 42; return null; });
            var next = shared.tryLock().orElseThrow();
            next.release();
        } finally {
            releaseSubscriber.countDown();
            original.close();
        }
    }

    @Test
    void concurrentAsyncHandoffAndTaskRetirementNeverPoisonsAnUnpublishedGrant()
            throws Exception {
        // Two carrier threads race Future publication against Task shutdown.
        // A failed/cancelled Future never conveyed a guarded payload; a
        // successful Future MUST be conservatively poisoned on abandonment.
        try (var scheduler = new OresScheduler(1)) {
            for (int iteration = 0; iteration < 64; iteration++) {
                var shared = OresMutex.shared(new int[] { iteration });
                var holder = shared.lock();
                var waiting = new AtomicReference<CompletableFuture<OresMutex.Guard<int[]>>>();
                var queued = new CountDownLatch(1);
                var never = new OresFuture<Integer>();
                TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                    waiting.set(shared.lockAsync());
                    queued.countDown();
                    return OresScheduler.await(never);
                });
                try {
                    assertTrue(queued.await(3, TimeUnit.SECONDS));
                    assertFalse(waiting.get().isDone());

                    var start = new CountDownLatch(1);
                    var canceller = Thread.ofPlatform().start(() -> {
                        try {
                            start.await();
                            task.cancel("handoff versus Task retirement");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    start.countDown();
                    // Physical permit handoff runs here, not on the Task
                    // carrier. Both possible Future completion outcomes are
                    // legal, but poisoning must agree with publication.
                    holder.release();
                    canceller.join(3000);
                    assertFalse(canceller.isAlive());

                    assertInstanceOf(TaskHandle.Cancelled.class,
                            task.getDone().get(4, TimeUnit.SECONDS));
                    assertTrue(task.cleanupFailures().get(3, TimeUnit.SECONDS).isEmpty());
                    var future = waiting.get();
                    boolean published = OresMutex.Shared.isPublishedAsyncGrant(future);
                    assertEquals(published, shared.isPoisoned(),
                            "poison must reflect Future grant publication at iteration "
                                    + iteration);
                    if (published) {
                        assertTrue(future.getNow(null).released());
                        shared.recover(state -> { state[0] = 0; return null; });
                    } else {
                        assertTrue(future.isCancelled(),
                                "undelivered cancelled requests cannot leak live Futures");
                    }
                    var next = shared.tryLock().orElseThrow();
                    next.release();
                } finally {
                    if (!holder.released()) holder.release();
                    task.cancel("cleanup test");
                }
            }
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

    /**
     * Holds a host-owned SharedMutex guard on a dedicated carrier. Host guards
     * belong to the acquiring thread, so the release (and therefore the async
     * waiter handoff) must run on that same carrier.
     */
    private static final class ForeignHolder<T> implements AutoCloseable {
        final Thread thread;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch acquired = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        ForeignHolder(OresMutex.Shared<T> shared) throws InterruptedException {
            thread = Thread.ofPlatform().name("foreign-mutex-holder").start(() -> {
                final OresMutex.Guard<T> guard;
                try {
                    guard = shared.lock();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                    acquired.countDown();
                    return;
                }
                acquired.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                try {
                    guard.release();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            assertTrue(acquired.await(3, TimeUnit.SECONDS), "holder must acquire");
            assertNull(failure.get(), "holder acquisition must not fail");
        }

        void releaseOnOwnerThread() {
            release.countDown();
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            thread.join(4000);
        }
    }
}
