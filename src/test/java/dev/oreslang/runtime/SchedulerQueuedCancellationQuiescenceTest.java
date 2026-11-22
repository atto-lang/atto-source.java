package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic queued-dispatch cancellation reproducer.
 * A Future getDone() must not become a physical-exit receipt while an
 * already accepted scheduler dispatch can still enter its guest context.
 */
final class SchedulerQueuedCancellationQuiescenceTest {
    @Test
    void getDoneDoesNotOutrunAcceptedButNotYetEnteredCarrierTurn() {
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
            TaskHandle<Void> child = TaskHandle.start(scheduler,
                    new OresScheduler.CleanupAwareTask<>() {
                        @Override
                        public OresScheduler.Step<Void> resume(OresScheduler.Resume resume) {
                            return OresScheduler.done(null);
                        }
                    });
            assertEquals(1, admitted.size(), "initial dispatch must be accepted");
            assertTrue(child.cancel("cancel before first entered turn"));
            assertFalse(child.getDone().isDone(),
                    "getDone must wait until queued dispatch can no longer enter guest context");

            Runnable admittedTurn = admitted.remove();
            admittedTurn.run();

            assertTrue(child.getDone().isDone(),
                    "physical task quiescence must publish after accepted dispatch retires");
            assertEquals(1, contextEntries.get());
        } finally {
            while (!admitted.isEmpty()) admitted.remove().run();
            scheduler.close();
        }
    }
}
