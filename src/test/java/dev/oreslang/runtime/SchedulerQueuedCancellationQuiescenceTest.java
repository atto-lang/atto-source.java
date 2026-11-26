package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic queued-dispatch cancellation reproducer.
 * Cancellation revokes queued dispatches and fences carriers which have
 * already acquired permission to enter the guest context.
 */
final class SchedulerQueuedCancellationQuiescenceTest {
    @Test
    void getDoneRevokesQueuedTurnBeforeItsCarrierEntersTheContext() {
        Queue<Runnable> admitted = new ArrayDeque<>();
        AtomicInteger contextEntries = new AtomicInteger();
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "cancel-admission-test", 1,
                admitted::add,
                turn -> {
                    contextEntries.incrementAndGet();
                    turn.run();
                });
        try {
            TaskHandle<Void> child = TaskHandle.start(scheduler, cleanupAwareTask());
            assertEquals(1, admitted.size(), "initial dispatch must be accepted");
            assertTrue(child.cancel("cancel before first entered turn"));
            assertTrue(child.getDone().isDone(),
                    "retirement can publish as soon as the queued turn is revoked");
            assertInstanceOf(TaskHandle.Cancelled.class, child.getDone().join());
            admitted.remove().run();
            assertEquals(0, contextEntries.get(),
                    "revoked work must skip the context admission boundary");
        } finally {
            while (!admitted.isEmpty()) admitted.remove().run();
            scheduler.close();
        }
    }

    @Test
    void getDoneWaitsForCarrierThatAlreadyClaimedContextAdmission() throws Exception {
        CountDownLatch admitting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Queue<Runnable> admitted = new ArrayDeque<>();
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "cancel-entered-admission-test", 1, admitted::add, turn -> {
                    admitting.countDown();
                    try {
                        assertTrue(release.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    turn.run();
                })) {
            TaskHandle<Void> child = TaskHandle.start(scheduler, cleanupAwareTask());
            Thread carrier = Thread.ofPlatform().start(admitted.remove());
            try {
                assertTrue(admitting.await(3, TimeUnit.SECONDS));
                assertTrue(child.cancel("cancel during context admission"));
                assertFalse(child.getDone().isDone(),
                        "a carrier with context admission still owns a physical lease");
            } finally {
                release.countDown();
                carrier.join(3_000);
            }
            assertFalse(carrier.isAlive());
            assertTrue(child.getDone().isDone(), "the context exit must publish retirement");
            assertInstanceOf(TaskHandle.Cancelled.class, child.getDone().join());
        }
    }

    @Test
    void replayedRevokedDispatchCannotEnterContextOrRetireTwice() {
        Queue<Runnable> admitted = new ArrayDeque<>();
        AtomicInteger contextEntries = new AtomicInteger();
        AtomicInteger retirements = new AtomicInteger();
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "cancel-replay-test", 1, admitted::add, turn -> {
                    contextEntries.incrementAndGet();
                    turn.run();
                })) {
            OresFuture<Integer> future = scheduler.start(resume -> {
                throw new AssertionError("revoked dispatch entered guest");
            }, retirements::incrementAndGet);
            assertEquals(1, admitted.size());
            assertTrue(future.cancel(false));
            assertEquals(1, retirements.get(), "unclaimed wrapper must not retain guest ownership");
            Runnable stale = admitted.remove();
            stale.run();
            stale.run();
            assertEquals(0, contextEntries.get(), "even a replay cannot regain admission");
            assertEquals(1, retirements.get());
            assertTrue(future.isCancelled());
        }
    }

    @Test
    void revokedWrapperDoesNotPreventIndependentTaskAdmission() throws Exception {
        Queue<Runnable> admitted = new ArrayDeque<>();
        AtomicInteger contextEntries = new AtomicInteger();
        AtomicInteger retirements = new AtomicInteger();
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "cancel-sibling-test", 1, admitted::add, turn -> {
                    contextEntries.incrementAndGet();
                    turn.run();
                })) {
            OresFuture<Integer> cancelled = scheduler.start(resume -> {
                throw new AssertionError("revoked dispatch entered guest");
            }, retirements::incrementAndGet);
            OresFuture<Integer> sibling = scheduler.start(resume -> OresScheduler.done(42));
            assertEquals(2, admitted.size());
            assertTrue(cancelled.cancel(false));
            assertEquals(1, retirements.get());
            admitted.remove().run();
            assertEquals(0, contextEntries.get());
            admitted.remove().run();
            assertEquals(42, sibling.get(3, TimeUnit.SECONDS));
            assertEquals(1, contextEntries.get());
            assertEquals(1, retirements.get());
        }
    }

    private static OresScheduler.CleanupAwareTask<Void> cleanupAwareTask() {
        return resume -> {
            throw new AssertionError("cancelled initial dispatch ran source code");
        };
    }
}
