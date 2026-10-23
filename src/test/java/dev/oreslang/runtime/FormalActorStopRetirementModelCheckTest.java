package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Bounded explicit-state contract for one terminating actor incarnation and
 * its potential supervisor successor. It does not run ActorRuntime: real
 * carrier, mailbox and Future traces must separately refine this model.
 */
final class FormalActorStopRetirementModelCheckTest {
    private enum Phase { RUNNING, STOPPING, QUIESCING, RETIRING, TERMINATED, QUARANTINED }
    private enum Action {
        STOP, CARRIER_EXIT, STOP_EFFECTS_DRAIN, CHILD_DONE, SOURCE_TASK_DONE,
        ENTER_QUIESCENCE, ENTER_RETIREMENT, LOCK_RECEIPT, CALLBACK_DRAIN,
        PUBLISH_DONE, RESTART_SUCCESSOR, CLEANUP_FAILURE, CALLBACK_FAILURE,
        DUPLICATE_STOP, STALE_GENERATION_COMPLETION
    }
    private static final int CARRIER = 1;
    private static final int STOP_EFFECTS = 2;
    private static final int CHILD = 4;
    private static final int SOURCE_TASK = 8;
    private static final int LOCK = 16;
    private static final int CALLBACK = 32;
    private static final int ALL = CARRIER | STOP_EFFECTS | CHILD | SOURCE_TASK | LOCK | CALLBACK;
    private static final int TO_QUIESCE = CARRIER | STOP_EFFECTS | CHILD;

    private record State(Phase phase, int pending, boolean admissionOpen,
            boolean memoryReclaimed, boolean done, boolean successorActive) {
        static State initial() {
            return new State(Phase.RUNNING, ALL, true, false, false, false);
        }
        State phase(Phase value) {
            return new State(value, pending, admissionOpen, memoryReclaimed, done, successorActive);
        }
        State clear(int bit) {
            return new State(phase, pending & ~bit, admissionOpen,
                    memoryReclaimed, done, successorActive);
        }
    }

    @Test
    void exhaustiveStopRestartStatesCannotPrematurelyReclaimOrRestart() {
        Set<State> reachable = explore();
        assertTrue(reachable.size() >= 50,
                "model must explore multiple independent receipt interleavings");
        assertTrue(reachable.stream().anyMatch(s -> s.phase() == Phase.QUARANTINED
                && s.pending() != 0), "faults must strand unretired resources");
        assertTrue(reachable.stream().anyMatch(s -> s.phase() == Phase.TERMINATED
                && s.successorActive()), "a successful replacement must be reachable");
        for (State s : reachable) assertInvariants(s);
    }

    @Test
    void firstFailureNeverPermitsSuccessfulDoneOrSuccessorAdmission() {
        State s = State.initial();
        s = step(s, Action.STOP).orElseThrow();
        s = step(s, Action.STOP_EFFECTS_DRAIN).orElseThrow();
        s = step(s, Action.CALLBACK_FAILURE).orElseThrow();
        assertEquals(Phase.QUARANTINED, s.phase());
        assertFalse(s.admissionOpen());
        assertFalse(s.done());
        assertFalse(s.memoryReclaimed());
        assertTrue(step(s, Action.PUBLISH_DONE).isEmpty());
        assertTrue(step(s, Action.RESTART_SUCCESSOR).isEmpty());
        // Late native completions cannot undo a failure fence.
        for (Action action : Action.values()) {
            if (action == Action.RESTART_SUCCESSOR || action == Action.PUBLISH_DONE)
                assertTrue(step(s, action).isEmpty());
        }
    }

    @Test
    void callbackDrainMustFollowClaimedHandoffBeforeDone() {
        State s = State.initial();
        s = step(s, Action.STOP).orElseThrow();
        s = step(s, Action.CARRIER_EXIT).orElseThrow();
        s = step(s, Action.STOP_EFFECTS_DRAIN).orElseThrow();
        s = step(s, Action.CHILD_DONE).orElseThrow();
        s = step(s, Action.ENTER_QUIESCENCE).orElseThrow();
        s = step(s, Action.SOURCE_TASK_DONE).orElseThrow();
        s = step(s, Action.ENTER_RETIREMENT).orElseThrow();
        s = step(s, Action.LOCK_RECEIPT).orElseThrow();
        assertEquals(CALLBACK, s.pending());
        assertTrue(step(s, Action.PUBLISH_DONE).isEmpty(),
                "a settled permit does not prove synchronous callbacks returned");
        s = step(s, Action.CALLBACK_DRAIN).orElseThrow();
        s = step(s, Action.PUBLISH_DONE).orElseThrow();
        assertTrue(s.done());
        assertTrue(step(s, Action.RESTART_SUCCESSOR).isPresent());
    }

    @Test
    void duplicateStopAndStaleGenerationCompletionsCannotAcquireAuthority() {
        State s = step(State.initial(), Action.STOP).orElseThrow();
        assertEquals(s, step(s, Action.DUPLICATE_STOP).orElseThrow());
        assertEquals(s, step(s, Action.STALE_GENERATION_COMPLETION).orElseThrow());
        assertTrue(step(s, Action.RESTART_SUCCESSOR).isEmpty());
    }

    private static Set<State> explore() {
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        queue.add(State.initial());
        seen.add(State.initial());
        while (!queue.isEmpty()) {
            State here = queue.removeFirst();
            for (Action action : Action.values()) {
                Optional<State> to = step(here, action);
                if (to.isPresent() && seen.add(to.orElseThrow())) {
                    queue.addLast(to.orElseThrow());
                }
            }
        }
        return Set.copyOf(seen);
    }

    private static Optional<State> step(State s, Action a) {
        if (a == Action.STALE_GENERATION_COMPLETION && s.phase() != Phase.RUNNING)
            return Optional.of(s); // incarnation mismatch has no authority
        if (a == Action.DUPLICATE_STOP && s.phase() != Phase.RUNNING)
            return Optional.of(s); // idempotent physical retirement claim
        if (a == Action.STOP && s.phase() == Phase.RUNNING)
            return Optional.of(new State(Phase.STOPPING, s.pending(), false, false, false, false));
        if (a == Action.RESTART_SUCCESSOR && s.phase() == Phase.TERMINATED
                && !s.successorActive())
            return Optional.of(new State(s.phase(), s.pending(), false, true, true, true));
        if (a == Action.CLEANUP_FAILURE || a == Action.CALLBACK_FAILURE) {
            if (s.phase() == Phase.STOPPING || s.phase() == Phase.QUIESCING
                    || s.phase() == Phase.RETIRING)
                return Optional.of(new State(Phase.QUARANTINED, s.pending(),
                        false, false, false, false));
            return Optional.empty();
        }
        if (s.phase() == Phase.RUNNING || s.phase() == Phase.TERMINATED)
            return Optional.empty();
        if (s.phase() == Phase.QUARANTINED) {
            // Physical callbacks may eventually finish, but cannot rescind
            // the supervisor quarantine authority decision.
            return switch (a) {
                case CARRIER_EXIT -> clear(s, CARRIER);
                case STOP_EFFECTS_DRAIN -> clear(s, STOP_EFFECTS);
                case CHILD_DONE -> clear(s, CHILD);
                case SOURCE_TASK_DONE -> clear(s, SOURCE_TASK);
                case LOCK_RECEIPT -> clear(s, LOCK);
                case CALLBACK_DRAIN -> clear(s, CALLBACK);
                default -> Optional.empty();
            };
        }
        return switch (a) {
            case CARRIER_EXIT -> clear(s, CARRIER);
            case STOP_EFFECTS_DRAIN -> clear(s, STOP_EFFECTS);
            case CHILD_DONE -> clear(s, CHILD);
            case SOURCE_TASK_DONE -> clear(s, SOURCE_TASK);
            case CALLBACK_DRAIN -> clear(s, CALLBACK);
            case ENTER_QUIESCENCE -> s.phase() == Phase.STOPPING
                    && (s.pending() & TO_QUIESCE) == 0
                    ? Optional.of(s.phase(Phase.QUIESCING)) : Optional.empty();
            case ENTER_RETIREMENT -> s.phase() == Phase.QUIESCING
                    && (s.pending() & SOURCE_TASK) == 0
                    ? Optional.of(s.phase(Phase.RETIRING)) : Optional.empty();
            case LOCK_RECEIPT -> s.phase() == Phase.RETIRING
                    ? clear(s, LOCK) : Optional.empty();
            case PUBLISH_DONE -> s.phase() == Phase.RETIRING && s.pending() == 0
                    ? Optional.of(new State(Phase.TERMINATED, 0, false, true, true, false))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static Optional<State> clear(State s, int bit) {
        return (s.pending() & bit) == 0 ? Optional.empty() : Optional.of(s.clear(bit));
    }

    private static void assertInvariants(State s) {
        assertEquals(s.phase() == Phase.RUNNING, s.admissionOpen(),
                "STOP must fence future admissions before any cleanup starts");
        if (s.phase() == Phase.QUIESCING || s.phase() == Phase.RETIRING
                || s.phase() == Phase.TERMINATED)
            assertEquals(0, s.pending() & TO_QUIESCE,
                    "finalization began with a physical carrier, child, or STOP effect live");
        if (s.phase() == Phase.RETIRING || s.phase() == Phase.TERMINATED)
            assertEquals(0, s.pending() & SOURCE_TASK,
                    "actor arena must outlive Task descendants and their lock receipts");
        assertEquals(s.phase() == Phase.TERMINATED, s.done(),
                "DONE is only for a fully completed incarnation");
        assertEquals(s.done(), s.memoryReclaimed(),
                "reclamation and successful DONE share the retirement gate");
        if (s.done()) assertEquals(0, s.pending(),
                "DONE preceded a carrier, Task, child, lock, or callback receipt");
        if (s.phase() == Phase.QUARANTINED) {
            assertFalse(s.done());
            assertFalse(s.memoryReclaimed());
            assertFalse(s.successorActive());
        }
        if (s.successorActive()) {
            assertEquals(Phase.TERMINATED, s.phase());
            assertEquals(0, s.pending());
        }
    }
}
