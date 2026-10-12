package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Bounded, exhaustively explored REFERENCE MODEL of two continuations
 * belonging to the same actor. It is not a machine-checked proof of the JVM
 * implementation: targeted executable scheduler races remain a separate gate.
 *
 * The physical actor lease is intentionally held until an EXIT step, even
 * after a task has returned Await/Cooperate or its Future has been cancelled.
 * Cancellation may publish a terminal Future before quiescence, but neither
 * actor nor global admission is released until BOTH quiescence and synchronous
 * notification draining have occurred. Each task may enter twice, bounding
 * the state space while preserving a real resume/second-turn transition.
 */
final class OresSchedulerStateModelTest {
    private static final int EMPTY = 0;
    private static final int QUEUED = 1;
    private static final int RUNNING = 2;
    private static final int WAITING = 3;
    private static final int TERMINAL = 4;
    private static final int MAX_TURNS = 2;

    private record Task(
            int phase,
            int turns,
            boolean executing,
            boolean resumeReady,
            boolean quiescent,
            boolean published,
            boolean drained,
            boolean released) {
        static Task empty() {
            return new Task(EMPTY, 0, false, false, false, false, false, false);
        }
        Task with(int newPhase, int newTurns, boolean newExecuting,
                boolean newResumeReady, boolean newQuiescent,
                boolean newPublished, boolean newDrained, boolean newReleased) {
            return new Task(newPhase, newTurns, newExecuting, newResumeReady,
                    newQuiescent, newPublished, newDrained, newReleased);
        }
        boolean admitted() {
            return phase != EMPTY;
        }
        boolean holdsAdmission() {
            return admitted() && !released;
        }
    }

    private record State(boolean closed, Task first, Task second) {
        Task task(int index) {
            return index == 0 ? first : second;
        }
        Task other(int index) {
            return index == 0 ? second : first;
        }
        State with(int index, Task next) {
            return index == 0 ? new State(closed, next, second)
                    : new State(closed, first, next);
        }
        State close() {
            return new State(true, terminateOnClose(first),
                    terminateOnClose(second));
        }
    }

    private static Task terminateOnClose(Task task) {
        if (!task.admitted() || task.phase == TERMINAL) return task;
        // The shutdown snapshot revokes all tasks before notifying any
        // subscribers. A physical carrier remains executing until EXIT.
        return task.with(TERMINAL, task.turns, task.executing, false,
                task.quiescent, true, task.drained, task.released);
    }

    private static List<State> successors(State state) {
        List<State> next = new ArrayList<>();
        if (!state.closed) next.add(state.close());
        for (int index = 0; index < 2; index++) {
            Task task = state.task(index);
            Task other = state.other(index);
            if (task.phase == EMPTY && !state.closed) {
                next.add(state.with(index, task.with(
                        QUEUED, 0, false, false, false, false, false, false)));
            }
            if (task.phase == QUEUED && !state.closed
                    && !other.executing && task.turns < MAX_TURNS) {
                next.add(state.with(index, task.with(RUNNING,
                        task.turns + 1, true, false, false, false, false, false)));
            }
            if (task.phase == RUNNING && task.executing) {
                // Await / Cooperate: the guest frame has not physically exited
                // the carrier yet, so no second task may start on this actor.
                if (task.turns < MAX_TURNS) {
                    next.add(state.with(index, task.with(
                            WAITING, task.turns, true, false,
                            false, false, false, false)));
                    next.add(state.with(index, task.with(
                            WAITING, task.turns, true, true,
                            false, false, false, false)));
                }
                // Normal completion is not publicly visible until quiescence.
                next.add(state.with(index, task.with(
                        TERMINAL, task.turns, true, false,
                        false, false, false, false)));
            }
            if (task.phase == WAITING && !task.resumeReady) {
                // Producer may complete while the old turn still owns carrier.
                next.add(state.with(index, task.with(
                        WAITING, task.turns, task.executing, true,
                        false, false, false, false)));
            }
            if (task.phase == WAITING && task.resumeReady && !task.executing) {
                next.add(state.with(index, task.with(
                        QUEUED, task.turns, false, false,
                        false, false, false, false)));
            }
            if (task.executing
                    && (task.phase == WAITING || task.phase == TERMINAL)) {
                next.add(state.with(index, task.with(task.phase,
                        task.turns, false, task.resumeReady,
                        task.quiescent, task.published, task.drained,
                        task.released)));
            }
            if (task.admitted() && task.phase != TERMINAL) {
                // Independent wall timeout can win while queued, waiting or
                // executing. A Future cancellation is visible immediately,
                // but carrier exit remains necessary for resource release.
                next.add(state.with(index, task.with(
                        TERMINAL, task.turns, task.executing, false,
                        false, false, false, false)));
                next.add(state.with(index, task.with(
                        TERMINAL, task.turns, task.executing, false,
                        false, true, false, false)));
            }
            if (task.phase == TERMINAL && !task.executing && !task.quiescent) {
                next.add(state.with(index, task.with(
                        TERMINAL, task.turns, false, false,
                        true, task.published, task.drained, task.released)));
            }
            if (task.phase == TERMINAL && task.quiescent && !task.published) {
                next.add(state.with(index, task.with(
                        TERMINAL, task.turns, false, false,
                        true, true, task.drained, task.released)));
            }
            if (task.published && !task.drained) {
                next.add(state.with(index, task.with(
                        TERMINAL, task.turns, task.executing, false,
                        task.quiescent, true, true, task.released)));
            }
            if (task.drained && task.quiescent && !task.released) {
                next.add(state.with(index, task.with(
                        TERMINAL, task.turns, false, false,
                        true, true, true, true)));
            }
        }
        return next;
    }

    private static void check(State state) {
        int active = (state.first.executing ? 1 : 0)
                + (state.second.executing ? 1 : 0);
        assertTrue(active <= 1,
                () -> "two simultaneous guest carriers on one actor: " + state);
        int reserved = (state.first.holdsAdmission() ? 1 : 0)
                + (state.second.holdsAdmission() ? 1 : 0);
        assertTrue(reserved >= 0 && reserved <= 2,
                () -> "unbounded source admission in model: " + state);
        for (Task task : List.of(state.first, state.second)) {
            assertFalse(task.quiescent && task.executing,
                    () -> "guest state reclaimed under running carrier: " + state);
            assertTrue(!task.released || (task.quiescent && task.drained),
                    () -> "timed permit reused before two release barriers: " + state);
            assertTrue(task.admitted() || (!task.executing && !task.quiescent
                    && !task.published && !task.drained && !task.released),
                    () -> "unadmitted task obtained execution authority: " + state);
            assertTrue(!task.quiescent || task.phase == TERMINAL,
                    () -> "nonterminal continuation resources retired: " + state);
            assertTrue(!task.released || task.phase == TERMINAL,
                    () -> "nonterminal timed permit released: " + state);
        }
    }

    @Test
    void checkerRejectsOverlappingCarriersAndPrematurePermitReuse() {
        Task active = new Task(RUNNING, 1, true, false,
                false, false, false, false);
        Task premature = new Task(TERMINAL, 1, false, false,
                false, true, true, true);
        assertThrows(AssertionError.class,
                () -> check(new State(false, active, active)),
                "two guest carriers on one actor must be rejected");
        assertThrows(AssertionError.class,
                () -> check(new State(false, premature, Task.empty())),
                "no permit may be reused before physical quiescence");
        Task reclaimedLive = new Task(TERMINAL, 1, true, false,
                true, true, true, true);
        assertThrows(AssertionError.class,
                () -> check(new State(true, reclaimedLive, Task.empty())),
                "terminal actor memory cannot retire while its carrier executes");
    }

    @Test
    void exhaustivelyExploreTwoTasksAcrossAwaitCloseCancellationAndTimeout() {
        State initial = new State(false, Task.empty(), Task.empty());
        Set<State> visited = new HashSet<>();
        ArrayDeque<State> work = new ArrayDeque<>();
        visited.add(initial);
        work.add(initial);
        boolean sawCancelledWhileExecuting = false;
        boolean sawDrainedBeforeQuiescence = false;
        boolean sawTwoReservedTasks = false;
        boolean sawBothReleased = false;
        boolean sawSecondCarrierTurn = false;
        while (!work.isEmpty()) {
            State state = work.removeFirst();
            check(state);
            for (Task t : List.of(state.first, state.second)) {
                if (t.phase == TERMINAL && t.executing && t.published)
                    sawCancelledWhileExecuting = true;
                if (t.drained && !t.quiescent) sawDrainedBeforeQuiescence = true;
                if (t.turns == 2) sawSecondCarrierTurn = true;
            }
            if (state.first.holdsAdmission() && state.second.holdsAdmission())
                sawTwoReservedTasks = true;
            if (state.first.released && state.second.released)
                sawBothReleased = true;
            for (State after : successors(state)) {
                check(after);
                if (visited.add(after)) work.addLast(after);
            }
            assertTrue(visited.size() < 200_000,
                    "bounded model unexpectedly exceeded state-space limit");
        }
        assertTrue(visited.size() > 500,
                "model exploration accidentally collapsed critical transitions");
        assertTrue(sawCancelledWhileExecuting,
                "must explore cancellation before guest carrier exit");
        assertTrue(sawDrainedBeforeQuiescence,
                "must explore permit retention after subscriber drain");
        assertTrue(sawTwoReservedTasks,
                "must explore competing tasks in the same actor");
        assertTrue(sawBothReleased,
                "must explore full shutdown/resource retirement");
        assertTrue(sawSecondCarrierTurn,
                "must explore real await/resume carrier migration");
    }
}
