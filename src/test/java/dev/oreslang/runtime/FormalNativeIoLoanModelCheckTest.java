package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive, source-free model of two physical native I/O loans.
 *
 * Logical Future failure, worker interruption and resource retirement are
 * distinct. Closing a resource requires physical worker exit AND terminal
 * notification drain, including the queued-never-started branch.
 */
final class FormalNativeIoLoanModelCheckTest {
    private enum Phase {
        ABSENT, ADMITTED, RUNNING, RETIRING, RETIRED,
        DISCARD_NOTIFICATIONS, CANCELLED_BEFORE_START
    }

    private record State(Phase a, Phase b, boolean shutdown, boolean resourceClosed) {}

    private static void enqueue(Set<State> visited, ArrayDeque<State> queue, State next) {
        if (visited.add(next)) queue.addLast(next);
    }

    private static boolean receipt(Phase phase) {
        return phase == Phase.RETIRED || phase == Phase.CANCELLED_BEFORE_START
                || phase == Phase.ABSENT;
    }

    @Test
    void noResourceReclamationBeforeBothPhysicalAndNotificationReceipts() {
        var visited = new HashSet<State>();
        var queue = new ArrayDeque<State>();
        enqueue(visited, queue, new State(Phase.ABSENT, Phase.ABSENT, false, false));
        boolean sawRunningDuringShutdown = false;
        boolean sawQueuedFailureNotification = false;
        boolean sawBlockedRetirement = false;
        boolean sawReleased = false;

        while (!queue.isEmpty()) {
            State s = queue.removeFirst();
            if (s.resourceClosed) {
                assertTrue(s.shutdown && receipt(s.a) && receipt(s.b),
                        "resource close requires every physical loan receipt");
                sawReleased = true;
                continue;
            }
            if (s.shutdown && (s.a == Phase.RUNNING || s.b == Phase.RUNNING)) {
                sawRunningDuringShutdown = true;
            }
            if (s.shutdown && (s.a == Phase.DISCARD_NOTIFICATIONS
                    || s.b == Phase.DISCARD_NOTIFICATIONS)) {
                sawQueuedFailureNotification = true;
            }
            if (s.shutdown && (!receipt(s.a) || !receipt(s.b))) {
                sawBlockedRetirement = true;
            }

            if (!s.shutdown) {
                enqueue(visited, queue, new State(s.a, s.b, true, false));
                if (s.a == Phase.ABSENT) {
                    enqueue(visited, queue, new State(Phase.ADMITTED, s.b, false, false));
                }
                if (s.b == Phase.ABSENT) {
                    enqueue(visited, queue, new State(s.a, Phase.ADMITTED, false, false));
                }
            }
            for (int child = 0; child < 2; child++) {
                Phase current = child == 0 ? s.a : s.b;
                if (current == Phase.ADMITTED) {
                    if (!s.shutdown) transition(visited, queue, s, child, Phase.RUNNING);
                    else transition(visited, queue, s, child, Phase.DISCARD_NOTIFICATIONS);
                } else if (current == Phase.RUNNING) {
                    // Interruption requests cannot jump directly to RETIRED.
                    transition(visited, queue, s, child, Phase.RETIRING);
                } else if (current == Phase.RETIRING) {
                    // A blocking Future subscriber keeps the worker loan live.
                    transition(visited, queue, s, child, Phase.RETIRED);
                } else if (current == Phase.DISCARD_NOTIFICATIONS) {
                    transition(visited, queue, s, child, Phase.CANCELLED_BEFORE_START);
                }
            }
            if (s.shutdown && receipt(s.a) && receipt(s.b)) {
                enqueue(visited, queue, new State(s.a, s.b, true, true));
            }
        }
        assertTrue(visited.size() >= 30, "bounded loan model needs meaningful state coverage");
        assertTrue(sawRunningDuringShutdown);
        assertTrue(sawQueuedFailureNotification);
        assertTrue(sawBlockedRetirement);
        assertTrue(sawReleased);
    }

    private static void transition(Set<State> visited, ArrayDeque<State> queue,
            State original, int child, Phase next) {
        enqueue(visited, queue, child == 0
                ? new State(next, original.b, original.shutdown, false)
                : new State(original.a, next, original.shutdown, false));
    }
}
