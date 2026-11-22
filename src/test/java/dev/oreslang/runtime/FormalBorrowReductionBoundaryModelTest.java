package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded state-space model of a reduction-budgeted logical actor turn.
 *
 * It deliberately models ownership and carrier handoff together, rather than
 * equating source-level rt cooperate with a Java Thread.yield() call.
 * This is a MODEL gate, not a statement that Graal/LLVM have equivalent
 * reduction-region instrumentation. That still needs guest execution.
 */
final class FormalBorrowReductionBoundaryModelTest {
    private enum Phase { RUNNING, HANDOFF, READY, CANCELLED }
    private enum Loan { NONE, ORDINARY, ACTOR_STATE, NATIVE }
    private enum Action {
        BORROW_ORDINARY, BORROW_STATE, BORROW_NATIVE, RELEASE,
        CHARGE_REDUCTION, COMPLETE_EXTERNAL_EFFECT,
        REQUEST_PREEMPTION, EXIT_CARRIER, RESUME, CANCEL
    }
    private record State(Phase phase, Loan loan, int workSinceYield,
                         int totalFuel, int resumes, int externalEffects,
                         boolean carrierOwned) {
        static State initial() {
            return new State(Phase.RUNNING, Loan.NONE, 0, 0, 0, 0, true);
        }
    }

    private static State step(State s, Action action, boolean unsafeIgnoreLoans) {
        if (s.phase == Phase.CANCELLED) return null;
        return switch (action) {
            case BORROW_ORDINARY, BORROW_STATE, BORROW_NATIVE -> {
                if (s.phase != Phase.RUNNING || s.loan != Loan.NONE) yield null;
                Loan kind = switch (action) {
                    case BORROW_ORDINARY -> Loan.ORDINARY;
                    case BORROW_STATE -> Loan.ACTOR_STATE;
                    case BORROW_NATIVE -> Loan.NATIVE;
                    default -> throw new IllegalStateException();
                };
                yield new State(s.phase, kind, s.workSinceYield, s.totalFuel,
                        s.resumes, s.externalEffects, s.carrierOwned);
            }
            case RELEASE -> s.phase == Phase.RUNNING && s.loan != Loan.NONE
                    ? new State(s.phase, Loan.NONE, s.workSinceYield, s.totalFuel,
                            s.resumes, s.externalEffects, s.carrierOwned) : null;
            case CHARGE_REDUCTION -> s.phase == Phase.RUNNING && s.totalFuel < 4
                    ? new State(s.phase, s.loan, Math.min(2, s.workSinceYield + 1),
                            s.totalFuel + 1, s.resumes, s.externalEffects, true) : null;
            case COMPLETE_EXTERNAL_EFFECT -> s.phase == Phase.RUNNING
                    && s.externalEffects == 0
                    ? new State(s.phase, s.loan, s.workSinceYield, s.totalFuel,
                            s.resumes, 1, true) : null;
            case REQUEST_PREEMPTION -> s.phase == Phase.RUNNING
                    && s.workSinceYield == 2
                    && s.resumes == 0
                    && (unsafeIgnoreLoans || s.loan == Loan.NONE)
                    ? new State(Phase.HANDOFF, s.loan, s.workSinceYield,
                            s.totalFuel, s.resumes, s.externalEffects, true) : null;
            case EXIT_CARRIER -> s.phase == Phase.HANDOFF
                    ? new State(Phase.READY, s.loan, s.workSinceYield,
                            s.totalFuel, s.resumes, s.externalEffects, false) : null;
            case RESUME -> s.phase == Phase.READY && !s.carrierOwned
                    ? new State(Phase.RUNNING, s.loan, 0, s.totalFuel,
                            s.resumes + 1, s.externalEffects, true) : null;
            // Cancellation abstracts completed cleanup/retirement: all loans
            // have already been retired before the terminal state is exposed.
            case CANCEL -> s.phase == Phase.RUNNING
                    ? new State(Phase.CANCELLED, Loan.NONE, s.workSinceYield,
                            s.totalFuel, s.resumes, s.externalEffects, false) : null;
        };
    }

    private static Set<State> explore(boolean unsafeIgnoreLoans) {
        Set<State> visited = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        visited.add(initial);
        queue.add(initial);
        while (!queue.isEmpty()) {
            State current = queue.removeFirst();
            for (Action action : Action.values()) {
                State next = step(current, action, unsafeIgnoreLoans);
                if (next != null && visited.add(next)) queue.addLast(next);
            }
        }
        return Set.copyOf(visited);
    }

    private static boolean safety(State state) {
        if (state.phase != Phase.RUNNING && state.loan != Loan.NONE) return false;
        if (state.phase == Phase.HANDOFF && !state.carrierOwned) return false;
        if (state.phase == Phase.READY && state.carrierOwned) return false;
        if (state.resumes > 0 && state.totalFuel < 2) return false;
        if (state.totalFuel < state.workSinceYield) return false;
        if (state.externalEffects > 1 || state.resumes > 1) return false;
        return true;
    }

    @Test
    void boundedExhaustiveInterleavingsKeepLoanAndFuelInvariants() {
        Set<State> states = explore(false);
        assertTrue(states.size() > 25, "must explore nontrivial scheduling choices");
        assertTrue(states.stream().anyMatch(s -> s.loan == Loan.ACTOR_STATE));
        assertTrue(states.stream().anyMatch(s -> s.loan == Loan.NATIVE));
        assertTrue(states.stream().anyMatch(s -> s.phase == Phase.READY && s.totalFuel >= 2));
        assertTrue(states.stream().anyMatch(s -> s.resumes == 1 && s.externalEffects == 1));
        states.forEach(s -> assertTrue(safety(s), () -> "unsafe state: " + s));

        // Budget exhaustion is not authority to suspend while a loan remains.
        for (Loan loan : new Loan[]{Loan.ORDINARY, Loan.ACTOR_STATE, Loan.NATIVE}) {
            State blocked = new State(Phase.RUNNING, loan, 2, 2, 0, 0, true);
            assertNull(step(blocked, Action.REQUEST_PREEMPTION, false));
            State released = step(blocked, Action.RELEASE, false);
            assertNotNull(step(released, Action.REQUEST_PREEMPTION, false));
        }
    }

    @Test
    void mutantYieldAcrossOutstandingLoanIsCaught() {
        Set<State> unsound = explore(true);
        for (Loan loan : new Loan[]{Loan.ORDINARY, Loan.ACTOR_STATE, Loan.NATIVE}) {
            assertTrue(unsound.stream().anyMatch(s -> s.phase == Phase.HANDOFF && s.loan == loan
                    && !safety(s)), () -> "mutant should violate loan liveness for " + loan);
        }
    }
}
