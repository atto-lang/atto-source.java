package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ActorAffinitySoftSpillTest {
    @Test
    void idleSoftLaneAcceptsOverflowWhilePreferredLaneIsBusy() throws Exception {
        ActorAffinityDispatcher dispatcher = new ActorAffinityDispatcher(
                // Spill semantics in isolation: idle stealing off.
                2, 4, new int[0], index -> Executors.newSingleThreadExecutor(), false, -1L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch migrated = new CountDownLatch(1);
        CountDownLatch completedPreferred = new CountDownLatch(2);
        AtomicInteger completedOnPreferred = new AtomicInteger();
        try {
            dispatcher.executeOnLane(0, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test preferred lane timed out");
                    }
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Threshold=4/2=2 queued jobs; the first two wait behind the
            // blocked worker, and the third is sent to the idle other lane.
            dispatcher.executeOnLane(0, () -> {
                completedOnPreferred.incrementAndGet();
                completedPreferred.countDown();
            });
            dispatcher.executeOnLane(0, () -> {
                completedOnPreferred.incrementAndGet();
                completedPreferred.countDown();
            });
            dispatcher.executeOnLane(0, migrated::countDown);
            assertTrue(migrated.await(5, TimeUnit.SECONDS),
                    "soft overflow should execute while preferred CPU lane is blocked");
            assertEquals(1, dispatcher.softSpills());
            assertEquals(0, completedOnPreferred.get(),
                    "preferred queue remains serialized behind its active turn");
            release.countDown();
            assertTrue(completedPreferred.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            dispatcher.shutdown();
            assertTrue(dispatcher.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(2, completedOnPreferred.get());
    }

    @Test
    void enqueuedThenRejectedLaneTurnCannotExecuteAfterAdmissionFailure() {
        class EnqueueThenThrow extends AbstractExecutorService {
            private boolean closed;
            private final AtomicInteger submissions = new AtomicInteger();
            private final AtomicReference<Runnable> rejectedRunnable = new AtomicReference<>();
            @Override public void execute(Runnable command) {
                if (submissions.getAndIncrement() == 0) {
                    rejectedRunnable.set(command);
                    throw new RejectedExecutionException("enqueued before failure");
                }
                command.run();
            }
            @Override public void shutdown() { closed = true; }
            @Override public List<Runnable> shutdownNow() { closed = true; return List.of(); }
            @Override public boolean isShutdown() { return closed; }
            @Override public boolean isTerminated() { return closed; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
                return closed;
            }
        }
        EnqueueThenThrow lane = new EnqueueThenThrow();
        ActorAffinityDispatcher dispatcher = new ActorAffinityDispatcher(
                1, 1, new int[0], i -> lane);
        AtomicInteger guestRuns = new AtomicInteger();
        try {
            assertThrows(RejectedExecutionException.class,
                    () -> dispatcher.executeOnLane(0, guestRuns::incrementAndGet));
            lane.rejectedRunnable.get().run();
            assertEquals(0, guestRuns.get(),
                    "executor rejection must fence even a retained stale runnable");
            // The rejection must return its capacity; with cap=1, a second
            // admission cannot proceed otherwise.
            dispatcher.executeOnLane(0, guestRuns::incrementAndGet);
            assertEquals(1, guestRuns.get());
            lane.rejectedRunnable.get().run();
            assertEquals(1, guestRuns.get());
        } finally {
            dispatcher.shutdownNow();
        }
    }

    @Test
    void shutdownRevokesAbandonedLaneRunnableEvenIfItRunsLate() {
        class DeferredExecutor extends AbstractExecutorService {
            private boolean closed;
            private final List<Runnable> pending = new java.util.ArrayList<>();
            @Override public void execute(Runnable command) { pending.add(command); }
            @Override public void shutdown() { closed = true; }
            @Override public List<Runnable> shutdownNow() {
                closed = true;
                return List.copyOf(pending);
            }
            @Override public boolean isShutdown() { return closed; }
            @Override public boolean isTerminated() { return closed; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
                return closed;
            }
        }
        DeferredExecutor worker = new DeferredExecutor();
        ActorAffinityDispatcher dispatcher = new ActorAffinityDispatcher(
                1, 1, new int[0], ignored -> worker);
        AtomicInteger executed = new AtomicInteger();
        dispatcher.executeOnLane(0, executed::incrementAndGet);
        Runnable abandoned = worker.pending.get(0);
        assertEquals(1, dispatcher.shutdownNow().size());
        for (int i = 0; i < 10_000; i++) abandoned.run();
        assertEquals(0, executed.get(), "abandoned lane turn must never enter actor code");
        assertTrue(dispatcher.isShutdown());
    }

    @Test
    void invalidLaneCannotLeakAdmissionCapacity() throws Exception {
        ActorAffinityDispatcher dispatcher = new ActorAffinityDispatcher(
                1, 1, new int[0], index -> Executors.newSingleThreadExecutor());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> dispatcher.executeOnLane(2, () -> {}));
            CountDownLatch ran = new CountDownLatch(1);
            dispatcher.executeOnLane(0, ran::countDown);
            assertTrue(ran.await(5, TimeUnit.SECONDS));
        } finally {
            dispatcher.shutdown();
            assertTrue(dispatcher.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
