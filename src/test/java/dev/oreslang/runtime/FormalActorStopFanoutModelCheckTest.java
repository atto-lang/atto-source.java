package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive two-sibling STOP admission/retirement model.
 *
 * This is a bounded, source-free model of the physical STOP dispatcher
 * protocol, NOT evidence that actual JVM/native hook execution is correct.
 * The real runtime test separately holds two revokers inside OresVM workers.
 */
final class FormalActorStopFanoutModelCheckTest {
    private static final int BOTH = 0b11;

    // A terminal rejection is recorded as a completed failed dispatch receipt.
    // Actor memory must remain quarantined when any dispatch/teardown fails.
    private record State(
            boolean sealed,
            int submitted,
            int receipts,
            int failed,
            boolean parentRevokerRetired,
            boolean finalized) {
        State {
            if ((submitted & ~BOTH) != 0 || (receipts & ~BOTH) != 0
                    || (failed & ~BOTH) != 0) {
                throw new IllegalArgumentException("invalid STOP receipt bitmask");
            }
        }
    }

    private static State initial() {
        return new State(false, 0, 0, 0, false, false);
    }

    private static void offer(Set<State> visited, ArrayDeque<State> queue, State next) {
        if (visited.add(next)) queue.add(next);
    }

    @Test
    void exhaustiveSiblingStopReceiptsPreventEarlyArenaFinalization() {
        var visited = new HashSet<State>();
        var queue = new ArrayDeque<State>();
        offer(visited, queue, initial());
        boolean sawBothSubmittedWithFirstStillBlocked = false;
        boolean sawRejectedSubmission = false;
        boolean sawCleanFinalization = false;

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            assertEquals(0, state.receipts & ~state.submitted,
                    "a receipt may never precede admitted STOP work");
            assertEquals(0, state.failed & ~state.receipts,
                    "a failure must have a physical/denied admission receipt");
            if (state.submitted != 0) {
                assertTrue(state.sealed,
                        "every descendant must be sealed before any stop hook starts");
            }
            if (state.finalized) {
                assertTrue(state.sealed && state.parentRevokerRetired);
                assertEquals(BOTH, state.submitted);
                assertEquals(BOTH, state.receipts);
                assertEquals(0, state.failed,
                        "failed native revocation must quarantine rather than reclaim");
                sawCleanFinalization = true;
                continue;
            }
            if (!state.sealed) {
                offer(visited, queue,
                        new State(true, 0, 0, 0, false, false));
                continue;
            }
            if (state.submitted == BOTH && state.receipts == 0) {
                sawBothSubmittedWithFirstStillBlocked = true;
            }
            if (state.failed != 0) sawRejectedSubmission = true;

            for (int bit : new int[] {1, 2}) {
                if ((state.submitted & bit) == 0) {
                    // Independent admission is possible even when the other
                    // child has been dispatched but its revoker is blocked.
                    offer(visited, queue,
                            new State(true, state.submitted | bit,
                                    state.receipts, state.failed,
                                    state.parentRevokerRetired, false));
                    // A saturated bounded executor rejects rather than
                    // silently dropping ownership or inventing success.
                    offer(visited, queue,
                            new State(true, state.submitted | bit,
                                    state.receipts | bit, state.failed | bit,
                                    state.parentRevokerRetired, false));
                } else if ((state.receipts & bit) == 0) {
                    offer(visited, queue,
                            new State(true, state.submitted,
                                    state.receipts | bit, state.failed,
                                    state.parentRevokerRetired, false));
                    // A physical cleanup callback that throws can complete
                    // its dispatch receipt but never authorize reclamation.
                    offer(visited, queue,
                            new State(true, state.submitted,
                                    state.receipts | bit, state.failed | bit,
                                    state.parentRevokerRetired, false));
                }
            }
            if (!state.parentRevokerRetired) {
                offer(visited, queue,
                        new State(true, state.submitted,
                                state.receipts, state.failed, true, false));
            }
            if (state.submitted == BOTH && state.receipts == BOTH
                    && state.failed == 0 && state.parentRevokerRetired) {
                offer(visited, queue,
                        new State(true, BOTH, BOTH, 0, true, true));
            }
        }
        assertTrue(visited.size() >= 25,
                "the model must explore independent admission, failure and retirement");
        assertTrue(sawBothSubmittedWithFirstStillBlocked,
                "STOP requests must be independent of sibling revoker completion");
        assertTrue(sawRejectedSubmission,
                "bounded executor rejection must be a quarantine state");
        assertTrue(sawCleanFinalization,
                "successful receipts must permit eventual physical finalization");
    }
}
