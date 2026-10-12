package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dependency-free bounded state exploration of owner-tagged ARM_RESULT delivery.
 *
 * This deliberately models execution-context publication separately from the
 * Java implementation. Refinement to SourceTask and the actor scheduler remains
 * a release gate. Owner identity includes task and incarnation, not just the
 * monotonically numbered lexical frame.
 */
final class FormalArmResultOwnershipModelCheckTest {
    private enum Phase {
        OUTER_RUNNING, INNER_RUNNING, INNER_UNWIND, OUTER_UNWIND, CLOSED
    }
    private enum Completion { NONE, INNER_RESULT, OUTER_RESULT, THROW }
    private enum Action {
        ENTER_INNER, SUSPEND, RESUME, INNER_RETURN, INNER_FAIL,
        INNER_CLEANUP, CONSUME_INNER, UNWIND_INNER_FAILURE,
        OUTER_RETURN, OUTER_FAIL, OUTER_CLEANUP, PUBLISH,
        CANCEL, DUPLICATE_RESUME
    }

    private record Frame(int taskId, int incarnation, int lexicalId) {}
    private record Envelope(Frame owner, int value) {}

    private record State(
            Phase phase, Completion pending, boolean waiting, boolean cancelled,
            int innerCleanup, int outerCleanup, int innerConsumptions,
            int publications) {
        static State initial() {
            return new State(Phase.OUTER_RUNNING, Completion.NONE, false,
                    false, 0, 0, 0, 0);
        }
        boolean closed() { return phase == Phase.CLOSED; }
    }

    private record Edge(State before, Action action, State after) {}

    @Test
    void ownerTicketMatchingRejectsCrossTaskIncarnationAndLexicalFrame() {
        List<Frame> frames = new ArrayList<>();
        for (int task = 1; task <= 2; task++) {
            for (int incarnation = 1; incarnation <= 2; incarnation++) {
                for (int lexicalId = 1; lexicalId <= 2; lexicalId++) {
                    frames.add(new Frame(task, incarnation, lexicalId));
                }
            }
        }

        int accepted = 0;
        for (Frame sender : frames) {
            Envelope result = new Envelope(sender, 42);
            for (Frame receiver : frames) {
                boolean authorized = receiver.equals(result.owner());
                if (authorized) accepted++;
                assertEquals(sender.equals(receiver), authorized);
            }
        }
        assertEquals(frames.size(), accepted,
                "every result has exactly one authorized frame among all candidates");

        // Negative mutation: checking only a local numeric lexical ID would
        // allow a stopped/restarted actor or another SourceTask to consume it.
        Envelope stale = new Envelope(new Frame(1, 1, 1), 42);
        Frame wrongIncarnation = new Frame(1, 2, 1);
        assertTrue(stale.owner().lexicalId() == wrongIncarnation.lexicalId(),
                "mutant is expected to accept a stale task incarnation");
        assertNotEquals(stale.owner(), wrongIncarnation,
                "correct composite owner check rejects the counterexample");
    }

    @Test
    void exhaustivelyExploreNestedAwaitCancellationAndCleanupOrder() {
        Set<State> states = new HashSet<>();
        List<Edge> edges = new ArrayList<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        states.add(initial);
        queue.add(initial);
        while (!queue.isEmpty()) {
            State current = queue.removeFirst();
            assertSafety(current);
            for (Action action : Action.values()) {
                Optional<State> next = step(current, action);
                if (next.isEmpty()) continue;
                State after = next.orElseThrow();
                assertSafety(after);
                edges.add(new Edge(current, action, after));
                if (states.add(after)) queue.addLast(after);
            }
            assertTrue(states.size() < 2000, "unexpected model state explosion");
        }
        assertTrue(states.size() >= 20,
                "must explore both successful and failing nested contexts");
        assertEquals(states, reachCanClose(states, edges),
                "all reachable states need a finite completion/cancellation path");
    }

    private static void assertSafety(State state) {
        assertTrue(state.innerCleanup() >= 0 && state.innerCleanup() <= 1);
        assertTrue(state.outerCleanup() >= 0 && state.outerCleanup() <= 1);
        assertTrue(state.innerConsumptions() >= 0 && state.innerConsumptions() <= 1);
        assertTrue(state.publications() >= 0 && state.publications() <= 1);
        if (state.phase() == Phase.OUTER_RUNNING && state.innerCleanup() == 1)
            assertEquals(1, state.innerConsumptions(),
                    "an inner ARM_RESULT must have been consumed once by its owner");
        if (state.waiting())
            assertTrue(state.phase() == Phase.INNER_RUNNING
                    || state.phase() == Phase.OUTER_RUNNING,
                    "unwinding frames cannot be executing an awaited producer");
        if (state.closed()) {
            assertEquals(1, state.publications());
            assertEquals(1, state.outerCleanup());
            assertFalse(state.waiting());
        } else assertEquals(0, state.publications());
    }

    private static Optional<State> step(State s, Action action) {
        if (action == Action.DUPLICATE_RESUME) return Optional.of(s);
        if (s.closed()) return Optional.empty();
        if (action == Action.CANCEL) {
            if (s.cancelled()) return Optional.empty();
            Phase phase = s.phase() == Phase.INNER_RUNNING
                    || s.phase() == Phase.INNER_UNWIND
                    ? Phase.INNER_UNWIND : Phase.OUTER_UNWIND;
            return Optional.of(new State(phase, Completion.THROW, false, true,
                    s.innerCleanup(), s.outerCleanup(), s.innerConsumptions(),
                    s.publications()));
        }
        if (action == Action.SUSPEND) {
            if (s.waiting() || s.cancelled()
                    || (s.phase() != Phase.INNER_RUNNING
                    && s.phase() != Phase.OUTER_RUNNING)) return Optional.empty();
            return Optional.of(new State(s.phase(), s.pending(), true, false,
                    s.innerCleanup(), s.outerCleanup(), s.innerConsumptions(),
                    s.publications()));
        }
        if (action == Action.RESUME) {
            if (!s.waiting() || s.cancelled()) return Optional.empty();
            return Optional.of(new State(s.phase(), s.pending(), false, false,
                    s.innerCleanup(), s.outerCleanup(), s.innerConsumptions(),
                    s.publications()));
        }
        if (s.waiting()) return Optional.empty();
        if (action == Action.ENTER_INNER) {
            if (s.phase() != Phase.OUTER_RUNNING || s.innerConsumptions() != 0)
                return Optional.empty();
            return Optional.of(new State(Phase.INNER_RUNNING, Completion.NONE, false,
                    s.cancelled(), 0, 0, 0, 0));
        }
        if (action == Action.INNER_RETURN || action == Action.INNER_FAIL) {
            if (s.phase() != Phase.INNER_RUNNING) return Optional.empty();
            return Optional.of(new State(Phase.INNER_UNWIND,
                    action == Action.INNER_FAIL ? Completion.THROW : Completion.INNER_RESULT,
                    false, s.cancelled(), 0, s.outerCleanup(), 0, 0));
        }
        if (action == Action.INNER_CLEANUP) {
            if (s.phase() != Phase.INNER_UNWIND || s.innerCleanup() != 0)
                return Optional.empty();
            return Optional.of(new State(s.phase(), s.pending(), false, s.cancelled(),
                    1, s.outerCleanup(), s.innerConsumptions(), 0));
        }
        if (action == Action.CONSUME_INNER) {
            if (s.phase() != Phase.INNER_UNWIND || s.innerCleanup() != 1
                    || s.pending() != Completion.INNER_RESULT || s.cancelled())
                return Optional.empty();
            return Optional.of(new State(Phase.OUTER_RUNNING, Completion.NONE, false,
                    false, 1, 0, 1, 0));
        }
        if (action == Action.UNWIND_INNER_FAILURE) {
            if (s.phase() != Phase.INNER_UNWIND || s.innerCleanup() != 1
                    || s.pending() != Completion.THROW) return Optional.empty();
            return Optional.of(new State(Phase.OUTER_UNWIND, Completion.THROW, false,
                    s.cancelled(), 1, 0, 0, 0));
        }
        if (action == Action.OUTER_RETURN || action == Action.OUTER_FAIL) {
            if (s.phase() != Phase.OUTER_RUNNING || s.cancelled())
                return Optional.empty();
            return Optional.of(new State(Phase.OUTER_UNWIND,
                    action == Action.OUTER_FAIL ? Completion.THROW : Completion.OUTER_RESULT,
                    false, false, s.innerCleanup(), 0, s.innerConsumptions(), 0));
        }
        if (action == Action.OUTER_CLEANUP) {
            if (s.phase() != Phase.OUTER_UNWIND || s.outerCleanup() != 0)
                return Optional.empty();
            return Optional.of(new State(s.phase(), s.pending(), false, s.cancelled(),
                    s.innerCleanup(), 1, s.innerConsumptions(), 0));
        }
        if (action == Action.PUBLISH) {
            if (s.phase() != Phase.OUTER_UNWIND || s.outerCleanup() != 1)
                return Optional.empty();
            return Optional.of(new State(Phase.CLOSED, s.pending(), false, s.cancelled(),
                    s.innerCleanup(), 1, s.innerConsumptions(), 1));
        }
        return Optional.empty();
    }

    private static Set<State> reachCanClose(Set<State> states, List<Edge> edges) {
        Map<State, List<State>> reverse = new HashMap<>();
        for (Edge e : edges)
            reverse.computeIfAbsent(e.after(), ignored -> new ArrayList<>())
                    .add(e.before());
        Set<State> reachable = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        for (State state : states)
            if (state.closed() && reachable.add(state)) queue.add(state);
        while (!queue.isEmpty())
            for (State predecessor : reverse.getOrDefault(queue.removeFirst(), List.of()))
                if (reachable.add(predecessor)) queue.add(predecessor);
        return reachable;
    }
}
