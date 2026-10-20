package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Parent terminal Futures must not retire while scoped descendants run. */
final class TaskScopeCancellationTest {
    @Test
    void cancellationKeepsScopeOpenUntilRunningCarrierUnwinds() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> child = scheduler.start(resume -> {
                ticket.beganExecution();
                childStarted.countDown();
                if (!releaseChild.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test child did not receive release");
                }
                return OresScheduler.done(42);
            }, ticket::quiesced);
            ticket.attach(child);
            try {
                assertTrue(childStarted.await(5, TimeUnit.SECONDS));
                OresFuture<Void> joining = scope.cancelAndDrain();
                assertTrue(child.isCancelled(), "scope must request child cancellation");
                assertFalse(joining.isDone(), "cancelled child is still in guest execution");
                assertFalse(joining.cancel(true), "lifetime join cannot be cancelled");
            } finally {
                releaseChild.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> scope.drained().get(5, TimeUnit.SECONDS));
        } finally {
            releaseChild.countDown();
        }
    }

    @Test
    void parentRunnerRetiresOnlyAfterDescendantPhysicalQuiescence() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        CountDownLatch parentStarted = new CountDownLatch(1);
        CountDownLatch parentRetired = new CountDownLatch(1);
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Void> child = scheduler.start(resume -> {
                ticket.beganExecution();
                childStarted.countDown();
                if (!releaseChild.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test release missing");
                }
                return OresScheduler.done(null);
            }, ticket::quiesced);
            ticket.attach(child);
            OresFuture<Void> parent = scheduler.start(new OresScheduler.Task<>() {
                @Override
                public OresScheduler.Step<Void> resume(OresScheduler.Resume resume) {
                    parentStarted.countDown();
                    return OresScheduler.await(new OresFuture<>());
                }

                @Override
                public void afterCarrierQuiescent(Runnable retire) {
                    scope.cancelAndDrain().whenCompleteRuntime((value, failure) -> retire.run());
                }
            }, parentRetired::countDown);
            try {
                assertTrue(childStarted.await(5, TimeUnit.SECONDS));
                assertTrue(parentStarted.await(5, TimeUnit.SECONDS));
                assertTrue(parent.cancel(false));
                assertFalse(parentRetired.await(100, TimeUnit.MILLISECONDS),
                        "parent must retain its scheduler lifecycle until child exits");
                assertTrue(child.isCancelled());
            } finally {
                releaseChild.countDown();
            }
            assertTrue(parentRetired.await(5, TimeUnit.SECONDS));
        } finally {
            releaseChild.countDown();
        }
    }

    @Test
    void cancellationBeforeFutureAttachmentStillCancelsLatePublication() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        OresFuture<Void> joining = scope.cancelAndDrain();
        OresFuture<Integer> late = new OresFuture<>();
        ticket.attach(late);
        assertTrue(late.isCancelled());
        assertFalse(joining.isDone(), "quiescence still required after cancellation");
        ticket.quiesced();
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> joining.get(5, TimeUnit.SECONDS));
    }
    @Test
    void cancellationSealsAdmissionEvenForStillExecutingParent() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration parent = scope.reserve();
        parent.beganExecution();
        OresFuture<Void> running = new OresFuture<>();
        parent.attach(running);
        OresFuture<Void> joined = scope.cancelAndDrain();
        assertTrue(running.isCancelled());
        assertThrows(IllegalStateException.class, () -> scope.reserve(parent));
        assertFalse(joined.isDone(), "parent still owns the physical carrier lease");
        parent.quiesced();
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> joined.get(5, TimeUnit.SECONDS));
    }

    @Test
    void cancellationAndAdmissionRaceCannotLeaveAnUntrackedChild() throws Exception {
        for (int i = 0; i < 128; i++) {
            TaskScope scope = new TaskScope();
            TaskScope.Registration parent = scope.reserve();
            parent.beganExecution();
            OresFuture<Void> parentFuture = new OresFuture<>();
            parent.attach(parentFuture);
            CountDownLatch gate = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<TaskScope.Registration> extra =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread spawner = Thread.ofVirtual().start(() -> {
                try {
                    gate.await();
                    extra.set(scope.reserve(parent));
                } catch (IllegalStateException expected) {
                    // Cancellation won the admission race.
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interruption);
                }
            });
            gate.countDown();
            OresFuture<Void> joined = scope.cancelAndDrain();
            spawner.join();
            TaskScope.Registration child = extra.get();
            if (child != null) {
                OresFuture<Void> childFuture = new OresFuture<>();
                child.attach(childFuture);
                assertTrue(childFuture.isCancelled(),
                        "late published work cannot survive scope cancellation");
                child.quiesced();
            }
            parent.quiesced();
            assertTrue(joined.isDone());
            assertThrows(IllegalStateException.class, () -> scope.reserve(parent));
        }
    }

    @Test
    void futureAttachmentRacingCancellationAlwaysRevokesTheChild() throws Exception {
        for (int attempt = 0; attempt < 128; attempt++) {
            TaskScope scope = new TaskScope();
            TaskScope.Registration ticket = scope.reserve();
            OresFuture<Void> future = new OresFuture<>();
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> unexpected =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread attacher = Thread.ofVirtual().start(() -> {
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("attachment gate did not open");
                    }
                    ticket.attach(future);
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            Thread canceller = Thread.ofVirtual().start(() -> {
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("cancellation gate did not open");
                    }
                    scope.cancelAndDrain();
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            start.countDown();
            attacher.join();
            canceller.join();
            assertNull(unexpected.get(), "cancellation/attachment raised an exception");
            assertTrue(future.isCancelled(), "attached child escaped scope cancellation");
            OresFuture<Void> joined = scope.drained();
            assertFalse(joined.isDone(), "terminal Future is not a quiescence witness");
            ticket.quiesced();
            assertTrue(joined.isCompletedExceptionally());
            assertSame(joined, scope.cancelAndDrain(), "cancellation must be idempotent");
            assertThrows(IllegalStateException.class, scope::reserve);
        }
    }


}
