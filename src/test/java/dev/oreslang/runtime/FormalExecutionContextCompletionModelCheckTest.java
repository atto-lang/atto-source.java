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
 * Bounded exhaustive transition model of two lexically nested value-match
 * execution contexts and an asynchronously resumable owning task.
 *
 * This is an ABSTRACT PROTOCOL MODEL, not a proof that SourceFlow currently
 * lowers ARM_RESULT: that lowering is deliberately gated by the compiler.
 * See docs/EXECUTION_CONTEXT_COMPLETION_FORMAL.md for refinement obligations.
 *
 * Bounds: two lexical match owners, one defer per frame, one primary failure,
 * two cleanup failures, one suspension per active phase, cancellation before
 * publication, and arbitrary late/duplicate callback notifications.
 */
final class FormalExecutionContextCompletionModelCheckTest {
    private enum Phase { INNER_RUNNING, INNER_UNWIND, OUTER_RUNNING, OUTER_UNWIND, CLOSED }
    private enum Completion { NONE, ARM_RESULT, THROW, CANCEL }
    private enum Failure { NONE, INNER, OUTER, CLEANUP, CANCEL }
    private enum Action {
        INNER_RETURN, INNER_THROW, OUTER_RETURN, OUTER_THROW,
        SUSPEND, RESUME, CANCEL,
        CLEANUP_OK, CLEANUP_FAIL, FINISH_INNER, FINISH_OUTER,
        LATE_RESUME, DUPLICATE_COMPLETION
    }

    private record State(
            Phase phase,
            Completion pending,
            Failure primary,
            boolean waiting,
            boolean cancelled,
            int innerDefers,
            int outerDefers,
            int innerResultsConsumed,
            int finalDeliveries,
            int suppressedFailures) {
        private static State initial() {
            return new State(Phase.INNER_RUNNING, Completion.NONE, Failure.NONE,
                    false, false, 0, 0, 0, 0, 0);
        }

        private boolean terminal() { return phase == Phase.CLOSED; }

        private State next(
                Phase p, Completion c, Failure f, boolean w, boolean stopped,
                int inner, int outer, int consumed, int delivered, int suppressed) {
            return new State(p, c, f, w, stopped, inner, outer, consumed, delivered, suppressed);
        }
    }

    private record Edge(State from, Action action, State to) {}

    @Test
    void everyReachableInterleavingPreservesOwnershipAndSingleDelivery() {
        State initial = State.initial();
        Set<State> discovered = new HashSet<>();
        List<Edge> edges = new ArrayList<>();
        ArrayDeque<State> work = new ArrayDeque<>();
        discovered.add(initial);
        work.add(initial);

        while (!work.isEmpty()) {
            State current = work.removeFirst();
            assertSafety(current);
            for (Action action : Action.values()) {
                Optional<State> next = step(current, action);
                if (next.isEmpty()) continue;
                State successor = next.orElseThrow();
                assertSafety(successor);
                edges.add(new Edge(current, action, successor));
                if (discovered.add(successor)) work.addLast(successor);
            }
            assertTrue(discovered.size() < 20_000, "state-space bound unexpectedly exceeded");
        }

        assertTrue(discovered.size() >= 40,
                "must explore errors, cancellation, suspension, and nested cleanup");
        Set<State> canComplete = reverseReachableFromTerminal(discovered, edges);
        assertEquals(discovered, canComplete,
                "each reachable nonterminal protocol state needs a finite exit path");

        for (State state : discovered) {
            assertEquals(state, step(state, Action.LATE_RESUME).orElseThrow(),
                    "stale callbacks cannot mutate execution-context state");
            if (state.terminal()) {
                assertEquals(state, step(state, Action.DUPLICATE_COMPLETION).orElseThrow(),
                        "terminal completion must be idempotent");
            }
        }
    }

    private static void assertSafety(State s) {
        assertTrue(s.innerDefers() >= 0 && s.innerDefers() <= 1);
        assertTrue(s.outerDefers() >= 0 && s.outerDefers() <= 1);
        assertTrue(s.innerResultsConsumed() >= 0 && s.innerResultsConsumed() <= 1,
                "an ARM_RESULT must be consumed by exactly one lexical owner");
        assertTrue(s.finalDeliveries() >= 0 && s.finalDeliveries() <= 1,
                "no result or failure can be delivered twice");
        assertTrue(s.suppressedFailures() >= 0 && s.suppressedFailures() <= 2);
        if (s.pending() == Completion.THROW || s.pending() == Completion.CANCEL)
            assertNotEquals(Failure.NONE, s.primary());
        if (s.primary() == Failure.NONE) assertEquals(0, s.suppressedFailures());
        if (s.waiting()) {
            assertTrue(s.phase() == Phase.INNER_RUNNING || s.phase() == Phase.OUTER_RUNNING,
                    "a cleanup or terminal context must not be awaiting a producer");
        }
        if (s.phase() == Phase.OUTER_RUNNING)
            assertEquals(1, s.innerResultsConsumed(),
                    "only the inner lexical boundary may consume its ARM_RESULT");
        if (s.terminal()) {
            assertEquals(1, s.finalDeliveries());
            assertFalse(s.waiting());
            assertEquals(1, s.outerDefers(), "cleanup precedes publication");
            if (s.pending() == Completion.ARM_RESULT) {
                assertEquals(1, s.innerResultsConsumed());
                assertEquals(Failure.NONE, s.primary(),
                        "an earlier failure cannot be replaced by a result");
                assertFalse(s.cancelled());
            }
        } else assertEquals(0, s.finalDeliveries());
    }

    private static Optional<State> step(State s, Action a) {
        if (a == Action.LATE_RESUME
                || (a == Action.DUPLICATE_COMPLETION && s.terminal()))
            return Optional.of(s);
        if (s.terminal()) return Optional.empty();

        if (a == Action.SUSPEND) {
            if (s.waiting() || s.cancelled()
                    || (s.phase() != Phase.INNER_RUNNING && s.phase() != Phase.OUTER_RUNNING))
                return Optional.empty();
            return Optional.of(s.next(s.phase(), s.pending(), s.primary(), true, false,
                    s.innerDefers(), s.outerDefers(), s.innerResultsConsumed(),
                    s.finalDeliveries(), s.suppressedFailures()));
        }
        if (a == Action.RESUME) {
            if (!s.waiting() || s.cancelled()) return Optional.empty();
            return Optional.of(s.next(s.phase(), s.pending(), s.primary(), false, false,
                    s.innerDefers(), s.outerDefers(), s.innerResultsConsumed(),
                    s.finalDeliveries(), s.suppressedFailures()));
        }
        if (a == Action.CANCEL) {
            if (s.cancelled()) return Optional.empty();
            Phase unwinding = (s.phase() == Phase.INNER_RUNNING
                    || s.phase() == Phase.INNER_UNWIND)
                    ? Phase.INNER_UNWIND : Phase.OUTER_UNWIND;
            boolean alreadyFailed = s.pending() == Completion.THROW;
            return Optional.of(s.next(unwinding,
                    alreadyFailed ? Completion.THROW : Completion.CANCEL,
                    s.primary() == Failure.NONE ? Failure.CANCEL : s.primary(),
                    false, true, s.innerDefers(), s.outerDefers(),
                    s.innerResultsConsumed(), s.finalDeliveries(), s.suppressedFailures()));
        }
        if (s.waiting()) return Optional.empty();

        if (a == Action.INNER_RETURN || a == Action.INNER_THROW) {
            if (s.phase() != Phase.INNER_RUNNING) return Optional.empty();
            boolean failure = a == Action.INNER_THROW;
            return Optional.of(s.next(Phase.INNER_UNWIND,
                    failure ? Completion.THROW : Completion.ARM_RESULT,
                    failure ? Failure.INNER : Failure.NONE, false, s.cancelled(),
                    s.innerDefers(), s.outerDefers(), 0, 0, s.suppressedFailures()));
        }
        if (a == Action.OUTER_RETURN || a == Action.OUTER_THROW) {
            if (s.phase() != Phase.OUTER_RUNNING) return Optional.empty();
            boolean failure = a == Action.OUTER_THROW;
            return Optional.of(s.next(Phase.OUTER_UNWIND,
                    failure ? Completion.THROW : Completion.ARM_RESULT,
                    failure ? Failure.OUTER : Failure.NONE, false, s.cancelled(),
                    s.innerDefers(), s.outerDefers(), s.innerResultsConsumed(), 0,
                    s.suppressedFailures()));
        }
        if (a == Action.CLEANUP_OK || a == Action.CLEANUP_FAIL) {
            boolean inner = s.phase() == Phase.INNER_UNWIND;
            if (!inner && s.phase() != Phase.OUTER_UNWIND) return Optional.empty();
            if (inner ? s.innerDefers() != 0 : s.outerDefers() != 0)
                return Optional.empty();
            boolean cleanupFailed = a == Action.CLEANUP_FAIL;
            Failure primary = s.primary() == Failure.NONE && cleanupFailed
                    ? Failure.CLEANUP : s.primary();
            // Cancellation and an existing throw keep their original
            // completion identity. Cleanup failure only supersedes a value.
            Completion completion = cleanupFailed
                    && (s.pending() == Completion.NONE
                        || s.pending() == Completion.ARM_RESULT)
                    ? Completion.THROW : s.pending();
            int suppressed = s.suppressedFailures()
                    + (cleanupFailed && s.primary() != Failure.NONE ? 1 : 0);
            return Optional.of(s.next(s.phase(), completion, primary, false,
                    s.cancelled(), inner ? 1 : s.innerDefers(),
                    inner ? s.outerDefers() : 1, s.innerResultsConsumed(),
                    0, suppressed));
        }
        if (a == Action.FINISH_INNER) {
            if (s.phase() != Phase.INNER_UNWIND || s.innerDefers() != 1)
                return Optional.empty();
            if (s.pending() == Completion.ARM_RESULT && !s.cancelled()) {
                return Optional.of(s.next(Phase.OUTER_RUNNING, Completion.NONE, Failure.NONE,
                        false, false, 1, 0, 1, 0, s.suppressedFailures()));
            }
            return Optional.of(s.next(Phase.OUTER_UNWIND, s.pending(), s.primary(),
                    false, s.cancelled(), 1, 0, 0, 0, s.suppressedFailures()));
        }
        if (a == Action.FINISH_OUTER) {
            if (s.phase() != Phase.OUTER_UNWIND || s.outerDefers() != 1)
                return Optional.empty();
            return Optional.of(s.next(Phase.CLOSED, s.pending(), s.primary(),
                    false, s.cancelled(), s.innerDefers(), 1,
                    s.innerResultsConsumed(), 1, s.suppressedFailures()));
        }
        return Optional.empty();
    }

    private static Set<State> reverseReachableFromTerminal(
            Set<State> states, List<Edge> edges) {
        Map<State, List<State>> incoming = new HashMap<>();
        for (Edge edge : edges)
            incoming.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge.from());
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> work = new ArrayDeque<>();
        for (State state : states) {
            if (state.terminal()) {
                seen.add(state);
                work.addLast(state);
            }
        }
        while (!work.isEmpty()) {
            for (State predecessor : incoming.getOrDefault(work.removeFirst(), List.of())) {
                if (seen.add(predecessor)) work.addLast(predecessor);
            }
        }
        return seen;
    }
}
