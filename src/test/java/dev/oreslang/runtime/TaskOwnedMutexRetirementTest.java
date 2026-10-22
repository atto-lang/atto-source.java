package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

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
            assertEquals(12, shared.tryLock().orElseThrow().value()[0]);
            // The verifier's final lock is a host guard, so release it.
            // Use a separate acquisition to keep this test explicit.
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
        var mutex = new AtomicReference<OresMutex.Local<int[]>>();
        var guard = new AtomicReference<OresMutex.Guard<int[]>>();
        var never = new OresFuture<Integer>();
        var acquired = new CountDownLatch(1);
        try (var scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> task = scheduler.startTracked(resume -> {
                var local = OresMutex.local(new int[] { 4 });
                mutex.set(local);
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
            TaskHandle<Integer> next = scheduler.startTracked(resume -> {
                var acquiredAgain = mutex.get().tryLock().orElseThrow();
                int value = acquiredAgain.value()[0];
                acquiredAgain.release();
                return OresScheduler.done(value);
            });
            assertEquals(4, next.result().get(3, TimeUnit.SECONDS));
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
