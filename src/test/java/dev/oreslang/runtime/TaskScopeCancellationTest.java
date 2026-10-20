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
}
