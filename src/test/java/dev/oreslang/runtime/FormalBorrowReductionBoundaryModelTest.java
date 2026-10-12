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
    private enum Phase { RUNNING, HANDOFF, READY, STOP_PENDING, CANCELLED }
    private enum Loan { NONE, ORDINARY, ACTOR_STATE, NATIVE }
    private enum Action {
        BORROW_ORDINARY, BORROW_STATE, BORROW_NATIVE, RELEASE,
        CHARGE_REDUCTION, COMPLETE_EXTERNAL_EFFECT,
        REQUEST_PREEMPTION, EXIT_CARRIER, RESUME,
        REQUEST_CANCEL, RETIRE_STOP_LOAN, EXIT_STOP_CARRIER
    }
    private record State(Phase phase, Loan loan, int workSinceYield,
                         int totalFuel, int resumes, int externalEffects,
                         boolean carrierOwned) {
        static State initial() {
            return new State(Phase.RUNNING, Loan.NONE, 0, 0, 0, 0, true);
        }
    }

    private static State step(State s, Action action, boolean unsafeIgnoreLoans) {
        return step(s, action, unsafeIgnoreLoans, false);
    }

    private static State step(
            State s, Action action, boolean unsafeIgnoreLoans, boolean unsafeReplayEffects) {
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
                    && s.externalEffects < (unsafeReplayEffects ? 2 : 1)
                    ? new State(s.phase, s.loan, s.workSinceYield, s.totalFuel,
                            s.resumes, s.externalEffects + 1, true) : null;
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
            // Cancellation seals admission but does not revoke the physical
            // carrier or silently erase live actor/native/ordinary loans.
            // The carrier must retire them before exposing DONE.
            case REQUEST_CANCEL -> {
                if (s.phase == Phase.READY && !s.carrierOwned
                        && s.loan == Loan.NONE) {
                    yield new State(Phase.CANCELLED, Loan.NONE,
                            s.workSinceYield, s.totalFuel, s.resumes,
                            s.externalEffects, false);
                }
                if ((s.phase == Phase.RUNNING || s.phase == Phase.HANDOFF)
                        && s.carrierOwned) {
                    yield new State(Phase.STOP_PENDING, s.loan,
                            s.workSinceYield, s.totalFuel, s.resumes,
                            s.externalEffects, true);
                }
                yield null;
            }
            case RETIRE_STOP_LOAN -> s.phase == Phase.STOP_PENDING
                    && s.carrierOwned && s.loan != Loan.NONE
                    ? new State(Phase.STOP_PENDING, Loan.NONE,
                            s.workSinceYield, s.totalFuel, s.resumes,
                            s.externalEffects, true) : null;
            case EXIT_STOP_CARRIER -> s.phase == Phase.STOP_PENDING
                    && s.carrierOwned && s.loan == Loan.NONE
                    ? new State(Phase.CANCELLED, Loan.NONE,
                            s.workSinceYield, s.totalFuel, s.resumes,
                            s.externalEffects, false) : null;
        };
    }

    private static Set<State> explore(boolean unsafeIgnoreLoans) {
        return explore(unsafeIgnoreLoans, false);
    }

    private static Set<State> explore(
            boolean unsafeIgnoreLoans, boolean unsafeReplayEffects) {
        Set<State> visited = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        visited.add(initial);
        queue.add(initial);
        while (!queue.isEmpty()) {
            State current = queue.removeFirst();
            for (Action action : Action.values()) {
                State next = step(current, action, unsafeIgnoreLoans, unsafeReplayEffects);
                if (next != null && visited.add(next)) queue.addLast(next);
            }
        }
        return Set.copyOf(visited);
    }

    private static boolean safety(State state) {
        if ((state.phase == Phase.HANDOFF
                || state.phase == Phase.READY
                || state.phase == Phase.CANCELLED)
                && state.loan != Loan.NONE) return false;
        if ((state.phase == Phase.RUNNING
                || state.phase == Phase.HANDOFF
                || state.phase == Phase.STOP_PENDING) && !state.carrierOwned) return false;
        if ((state.phase == Phase.READY || state.phase == Phase.CANCELLED)
                && state.carrierOwned) return false;
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
    void cancellationRequiresPhysicalCarrierExitAfterLoanRetirement() {
        for (Loan loan : new Loan[]{Loan.ORDINARY, Loan.ACTOR_STATE, Loan.NATIVE}) {
            State active = new State(Phase.RUNNING, loan, 2, 2, 0, 1, true);
            State stoppedAdmission = step(active, Action.REQUEST_CANCEL, false);
            assertNotNull(stoppedAdmission);
            assertEquals(Phase.STOP_PENDING, stoppedAdmission.phase);
            assertEquals(loan, stoppedAdmission.loan,
                    "cancellation must not silently drop an active loan");
            assertTrue(stoppedAdmission.carrierOwned);
            assertNull(step(stoppedAdmission, Action.EXIT_STOP_CARRIER, false),
                    "physical DONE is forbidden until the active loan retires");
            State retired = step(stoppedAdmission, Action.RETIRE_STOP_LOAN, false);
            assertNotNull(retired);
            assertEquals(Loan.NONE, retired.loan);
            State done = step(retired, Action.EXIT_STOP_CARRIER, false);
            assertNotNull(done);
            assertEquals(Phase.CANCELLED, done.phase);
            assertFalse(done.carrierOwned);
            assertTrue(safety(done));
        }

        State ready = new State(Phase.READY, Loan.NONE, 2, 2, 0, 1, false);
        State cancelledReady = step(ready, Action.REQUEST_CANCEL, false);
        assertNotNull(cancelledReady);
        assertTrue(safety(cancelledReady));
        assertEquals(Phase.CANCELLED, cancelledReady.phase);
    }

    @Test
    void replayMutationMustViolateExactlyOnceExternalEffectInvariant() {
        Set<State> safe = explore(false, false);
        assertTrue(safe.stream().allMatch(state -> state.externalEffects <= 1),
                "safe transitions must never double-commit an external effect");

        Set<State> replay = explore(false, true);
        assertTrue(replay.stream().anyMatch(state ->
                        state.externalEffects == 2 && !safety(state)),
                "the intentionally replaying transition must be detected");

        State once = new State(Phase.RUNNING, Loan.NONE, 0, 0, 0, 1, true);
        assertNull(step(once, Action.COMPLETE_EXTERNAL_EFFECT, false, false),
                "safe completion publication must consume its one-use token");
        State duplicated = step(once, Action.COMPLETE_EXTERNAL_EFFECT, false, true);
        assertNotNull(duplicated);
        assertEquals(2, duplicated.externalEffects);
        assertFalse(safety(duplicated));
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
