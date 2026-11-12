package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive bounded model of the VM's physical STOP queue arbitration.
 *
 * Logical terminal cancellation and physical task retirement are deliberately
 * different states. A discarded ADMITTED job is a terminal FAILURE receipt;
 * shutdownNow is never itself a receipt for a RUNNING native revoker.
 */
final class FormalPhysicalStopJobModelCheckTest {
    private enum Phase {
        NOT_SUBMITTED, ADMITTED, RUNNING, RETIRED,
        CANCELLED_BEFORE_START, REJECTED
    }

    private record State(
            Phase left,
            Phase right,
            Phase third,
            boolean vmClosed,
            boolean parentDone) {
        State {
            if (left == null || right == null || third == null) {
                throw new NullPointerException();
            }
        }
    }

    @Test
    void shutdownAndWorkerStartupNeverForgeSuccessfulPhysicalRetirement() {
        State initial = new State(Phase.NOT_SUBMITTED, Phase.NOT_SUBMITTED,
                Phase.NOT_SUBMITTED, false, false);
        ArrayDeque<State> pending = new ArrayDeque<>();
        Set<State> visited = new HashSet<>();
        add(visited, pending, initial);
        boolean sawDiscard = false;
        boolean sawFailedAdmission = false;
        boolean sawRunningDuringShutdown = false;
        boolean sawCleanParentDone = false;
        while (!pending.isEmpty()) {
            State current = pending.removeFirst();
            int running = (current.left == Phase.RUNNING ? 1 : 0)
                    + (current.right == Phase.RUNNING ? 1 : 0)
                    + (current.third == Phase.RUNNING ? 1 : 0);
            int waiting = (current.left == Phase.ADMITTED ? 1 : 0)
                    + (current.right == Phase.ADMITTED ? 1 : 0)
                    + (current.third == Phase.ADMITTED ? 1 : 0);
            assertTrue(running <= 1 && running + waiting <= 2,
                    "bounded one-worker one-queue capacity must hold");
            if (current.vmClosed) {
                assertEquals(0, waiting,
                        "shutdown drains every never-started admitted job");
            }
            if (current.parentDone) {
                assertEquals(Phase.RETIRED, current.left);
                assertEquals(Phase.RETIRED, current.right);
                assertEquals(Phase.RETIRED, current.third);
                sawCleanParentDone = true;
                continue;
            }
            if (current.left == Phase.CANCELLED_BEFORE_START
                    || current.right == Phase.CANCELLED_BEFORE_START
                    || current.third == Phase.CANCELLED_BEFORE_START) sawDiscard = true;
            if (current.left == Phase.REJECTED
                    || current.right == Phase.REJECTED
                    || current.third == Phase.REJECTED) sawFailedAdmission = true;
            if (current.vmClosed && running != 0) sawRunningDuringShutdown = true;

            if (!current.vmClosed) {
                // The single worker may be occupied while exactly one other
                // job is queued. Admission is not a resource release.
                for (int i = 0; i < 3; i++) {
                    Phase existing = i == 0 ? current.left
                            : i == 1 ? current.right : current.third;
                    if (existing != Phase.NOT_SUBMITTED) continue;
                    Phase nextPhase = running + waiting >= 2
                            ? Phase.REJECTED : Phase.ADMITTED;
                    transition(visited, pending, current, i, nextPhase);
                }
            }
            for (int i = 0; i < 3; i++) {
                Phase existing = i == 0 ? current.left
                        : i == 1 ? current.right : current.third;
                if (existing == Phase.ADMITTED && running == 0 && !current.vmClosed) {
                    transition(visited, pending, current, i, Phase.RUNNING);
                }
                if (existing == Phase.RUNNING) {
                    transition(visited, pending, current, i, Phase.RETIRED);
                }
            }
            if (!current.vmClosed) {
                // shutdownNow atomically discards all queued admissions,
                // but a running operation must exit by its own physical path.
                add(visited, pending, new State(
                        current.left == Phase.ADMITTED
                                ? Phase.CANCELLED_BEFORE_START : current.left,
                        current.right == Phase.ADMITTED
                                ? Phase.CANCELLED_BEFORE_START : current.right,
                        current.third == Phase.ADMITTED
                                ? Phase.CANCELLED_BEFORE_START : current.third,
                        true, false));
            }
            if (current.left == Phase.RETIRED && current.right == Phase.RETIRED
                    && current.third == Phase.RETIRED) {
                add(visited, pending, new State(current.left, current.right,
                        current.third, current.vmClosed, true));
            }
        }
        assertTrue(visited.size() >= 15, "model must explore shutdown/start races");
        assertTrue(sawDiscard, "queued operation discarded without starting");
        assertTrue(sawFailedAdmission, "bounded queue rejection visited");
        assertTrue(sawRunningDuringShutdown,
                "shutdown must not forge a running operation's retirement");
        assertTrue(sawCleanParentDone,
                "successfully retired children must allow clean parent DONE");
    }

    private static void transition(Set<State> visited, ArrayDeque<State> pending,
            State current, int child, Phase phase) {
        add(visited, pending, child == 0
                ? new State(phase, current.right, current.third, current.vmClosed, false)
                : child == 1
                        ? new State(current.left, phase, current.third, current.vmClosed, false)
                        : new State(current.left, current.right, phase, current.vmClosed, false));
    }

    private static void add(Set<State> visited, ArrayDeque<State> pending, State next) {
        if (visited.add(next)) pending.addLast(next);
    }
}
