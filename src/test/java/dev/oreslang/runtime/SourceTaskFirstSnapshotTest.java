package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Future.first must use one frozen candidate list for both racing and
 * cancellation authority. A mutable input list must never add or remove a
 * task's cancellation capability after the race has been subscribed.
 */
final class SourceTaskFirstSnapshotTest {
    private static TaskHandle<Integer> suspendedTask(
            OresScheduler scheduler, OresFuture<Void> gate) {
        TaskHandle<Integer> handle = TaskHandle.start(scheduler,
                resume -> resume.initial()
                        ? OresScheduler.<Integer>await(gate)
                        : OresScheduler.done(42));
        SourceTaskHandles.register(handle);
        return handle;
    }

    @Test
    @Timeout(10)
    void lateAddedOwnedTaskIsNotCancelledByAnEarlierRace() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> winning = new OresFuture<>();
            OresFuture<Integer> borrowed = new OresFuture<>();
            ArrayList<Object> candidates = new ArrayList<>(List.of(winning, borrowed));
            OresFuture<Object> race = SourceTaskHandles.first(candidates);

            OresFuture<Void> gate = new OresFuture<>();
            TaskHandle<Integer> unrelated = suspendedTask(scheduler, gate);
            candidates.add(unrelated.result()); // NOT a participant in the race.

            assertTrue(winning.completeFromRuntime(7));
            assertEquals(7, race.get(5, TimeUnit.SECONDS));
            assertFalse(unrelated.isCancellationRequested(),
                    "later-added source task must not inherit race cancellation");
            assertFalse(borrowed.isCancelled(), "borrowed input is observation-only");

            assertTrue(gate.completeFromRuntime(null));
            assertInstanceOf(TaskHandle.Success.class,
                    unrelated.getDone().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(10)
    void removingOwnedLoserFromCallerListCannotRevokeCancellation() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> winning = new OresFuture<>();
            OresFuture<Void> gate = new OresFuture<>();
            TaskHandle<Integer> ownedLoser = suspendedTask(scheduler, gate);
            ArrayList<Object> candidates = new ArrayList<>(
                    List.of(winning, ownedLoser.result()));
            OresFuture<Object> race = SourceTaskHandles.first(candidates);

            candidates.clear(); // Must not erase an already captured authority.
            assertTrue(winning.completeFromRuntime(9));
            assertEquals(9, race.get(5, TimeUnit.SECONDS));
            assertTrue(ownedLoser.isCancellationRequested(),
                    "original owned loser must still receive cancellation");
            assertInstanceOf(TaskHandle.Cancelled.class,
                    ownedLoser.getDone().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void boundedSnapshotRejectsOversizeBeforeSubscribingOrAllocating() {
        OresFuture<Integer> pending = new OresFuture<>();
        List<OresFuture<Integer>> tooMany =
                Collections.nCopies(OresFutures.MAX_FAN_IN + 1, pending);
        assertThrows(IllegalArgumentException.class,
                () -> SourceTaskHandles.first(tooMany));
        assertFalse(pending.isDone());
    }

    @Test
    void malformedCandidateListsAreRejectedBeforeSubscription() {
        assertThrows(NullPointerException.class, () -> SourceTaskHandles.first(null));
        assertThrows(NullPointerException.class,
                () -> SourceTaskHandles.first(new ArrayList<>(java.util.Arrays.asList(
                        new OresFuture<Integer>(), null))));
    }
}
