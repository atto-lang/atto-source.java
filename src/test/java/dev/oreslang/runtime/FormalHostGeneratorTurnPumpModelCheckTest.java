package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustively checks a bounded abstraction of the host-generator pump.
 *
 * This is NOT a refinement proof of GraalVM context entry/leave. The concrete
 * latch regressions check the implemented Java queue, and separate Truffle
 * guest tests are required before an external async generator can be enabled.
 */
final class FormalHostGeneratorTurnPumpModelCheckTest {
    private enum Event {
        ENQUEUE, OWNER_DISPATCH, FOREIGN_DISPATCH, WRONG_INCARNATION,
        SETTLE_RESULT, PHYSICAL_EXIT, CANCEL_PENDING, CANCEL_ACTIVE,
        SEAL_CONTEXT, STARTER_FAILED_NO_EXIT_RECEIPT,
        FAILED_EXIT_RECEIPT, PUBLISH_AFTER_EXIT, RETRY_CLOSE
    }

    private record State(
            int queued, int started, int published,
            boolean active, boolean resultReady, boolean physicalExit,
            boolean cancelled, boolean sealed, boolean retired,
            boolean unprovenExit) {
        static State initial() {
            return new State(0, 0, 0, false, false, false, false, false, false,
                    false);
        }
    }

    private static State step(State s, Event event) {
        int queued = s.queued, started = s.started, published = s.published;
        boolean active = s.active, result = s.resultReady;
        boolean exited = s.physicalExit, cancelled = s.cancelled;
        boolean sealed = s.sealed, retired = s.retired;
        boolean unprovenExit = s.unprovenExit;
        switch (event) {
            case ENQUEUE -> {
                if (!sealed && queued < 2) queued++;
            }
            case OWNER_DISPATCH -> {
                // Bound total activations as well as queue depth; limiting
                // just queue size still permits infinitely many sequential
                // producer starts and defeats exhaustive exploration.
                if (!sealed && !active && queued > 0 && started < 2) {
                    queued--;
                    started++;
                    active = true;
                    result = false;
                    exited = false;
                    cancelled = false;
                }
            }
            case FOREIGN_DISPATCH, WRONG_INCARNATION -> {
                // These have no authority to mutate a guest activation.
            }
            case SETTLE_RESULT -> {
                if (active) result = true;
            }
            case PHYSICAL_EXIT -> {
                if (active) exited = true;
            }
            case CANCEL_PENDING -> {
                if (queued > 0) queued--;
            }
            case CANCEL_ACTIVE -> {
                if (active) {
                    cancelled = true;
                    result = true; // Public cancellation is early.
                }
            }
            case SEAL_CONTEXT -> {
                sealed = true;
                queued = 0;
                if (active) {
                    cancelled = true;
                    result = true;
                }
            }
            case FAILED_EXIT_RECEIPT -> {
                if (active) {
                    // Cancellation/failure of a physical exit Future is not a
                    // host-owned DONE receipt. The public result may settle,
                    // but the context remains quarantined permanently.
                    sealed = true;
                    queued = 0;
                    cancelled = true;
                    result = true;
                    exited = true;
                    unprovenExit = true;
                }
            }
            case STARTER_FAILED_NO_EXIT_RECEIPT -> {
                if (active && !exited) {
                    // A consumer may cancel or seal WHILE start() runs.
                    // That public result is already settled, but a subsequent
                    // starter failure without a DONE receipt must still poison
                    // the physical context incarnation permanently.
                    active = false;
                    queued = 0;
                    sealed = true;
                    result = false;
                    exited = false;
                    cancelled = false;
                    unprovenExit = true;
                }
            }
            case PUBLISH_AFTER_EXIT -> {
                if (active && result && exited) {
                    active = false;
                    result = false;
                    exited = false;
                    cancelled = false;
                    published++;
                }
            }
            case RETRY_CLOSE -> {
                if (sealed && queued == 0 && !active && !unprovenExit) retired = true;
            }
        }
        return new State(queued, started, published, active, result, exited,
                cancelled, sealed, retired, unprovenExit);
    }

    private static void invariant(State s) {
        assertTrue(s.queued >= 0 && s.queued <= 2, "bounded FIFO capacity violated");
        assertTrue(s.started >= 0 && s.started <= 2,
                "model must bound total activations to remain finite");
        assertTrue(s.started >= s.published,
                "publication precedes ownership");
        assertTrue(s.started - s.published <= 1,
                "two guest turns were admitted before the first published");
        if (!s.active) {
            assertFalse(s.resultReady || s.physicalExit || s.cancelled,
                    "inactive activation retained source-task state");
        }
        if (s.unprovenExit) {
            assertTrue(s.sealed && !s.retired,
                    "unknown producer exit must quarantine context");
        }
        if (s.retired) {
            assertTrue(s.sealed && s.queued == 0 && !s.active
                            && !s.unprovenExit,
                    "context physically retired while guest still owned a turn");
        }
    }

    @Test
    void allReachableTwoSlotInterleavingsPreservePhysicalOwnership() {
        Set<State> visited = new HashSet<>();
        ArrayDeque<State> frontier = new ArrayDeque<>();
        State initial = State.initial();
        visited.add(initial);
        frontier.add(initial);
        boolean sawResultBeforeExit = false, sawCancelledWithoutExit = false;
        boolean sawTwoSequentialTurns = false, sawRetirement = false;
        while (!frontier.isEmpty()) {
            State state = frontier.removeFirst();
            invariant(state);
            sawResultBeforeExit |= state.active && state.resultReady
                    && !state.physicalExit;
            sawCancelledWithoutExit |= state.active && state.cancelled
                    && !state.physicalExit;
            sawTwoSequentialTurns |= state.started >= 2;
            sawRetirement |= state.retired;
            for (Event event : Event.values()) {
                State next = step(state, event);
                invariant(next);
                if (event == Event.FOREIGN_DISPATCH
                        || event == Event.WRONG_INCARNATION) {
                    assertEquals(state, next,
                            "foreign identity unexpectedly acquired guest authority");
                }
                if (event == Event.OWNER_DISPATCH && state.active) {
                    assertEquals(state, next,
                            "owner may not overlap a physically active guest turn");
                }
                if (event == Event.RETRY_CLOSE && state.active) {
                    assertFalse(next.retired,
                            "close cannot succeed on early Future cancellation");
                }
                if (visited.add(next)) frontier.addLast(next);
            }
        }
        assertTrue(visited.size() >= 25, "model lost important interleavings");
        assertTrue(sawResultBeforeExit && sawCancelledWithoutExit
                && sawTwoSequentialTurns && sawRetirement,
                "expected safety-relevant states not reachable");
    }

    @Test
    void failedExitReceiptCannotRetireEvenAfterPublicCancellation() {
        State state = State.initial();
        state = step(state, Event.ENQUEUE);
        state = step(state, Event.OWNER_DISPATCH);
        state = step(state, Event.FAILED_EXIT_RECEIPT);
        assertTrue(state.unprovenExit);
        state = step(state, Event.PUBLISH_AFTER_EXIT);
        assertFalse(state.active);
        state = step(state, Event.RETRY_CLOSE);
        assertFalse(state.retired);
        invariant(state);
    }

    @Test
    void failedStarterWithoutExitReceiptIsNotQuiescence() {
        State state = State.initial();
        state = step(state, Event.ENQUEUE);
        state = step(state, Event.OWNER_DISPATCH);
        state = step(state, Event.STARTER_FAILED_NO_EXIT_RECEIPT);
        assertTrue(state.unprovenExit);
        assertTrue(state.sealed);
        assertFalse(state.active);
        state = step(state, Event.RETRY_CLOSE);
        assertFalse(state.retired,
                "no proof of producer termination means no safe retirement");
        invariant(state);
    }

    @Test
    void starterFailureAfterCancellationStillQuarantinesPhysicalExit() {
        State state = State.initial();
        state = step(state, Event.ENQUEUE);
        state = step(state, Event.ENQUEUE);
        state = step(state, Event.OWNER_DISPATCH);
        state = step(state, Event.CANCEL_ACTIVE);
        assertTrue(state.resultReady && state.active);
        state = step(state, Event.STARTER_FAILED_NO_EXIT_RECEIPT);
        assertTrue(state.unprovenExit && state.sealed);
        assertFalse(state.active);
        assertEquals(0, state.queued,
                "unknown startup outcome must cancel other queued pulls");
        state = step(state, Event.RETRY_CLOSE);
        assertFalse(state.retired,
                "cancelled public result is not a physical DONE receipt");
        invariant(state);
    }

    /**
     * A second bounded abstraction for certificate authority. Synthetic,
     * foreign and replayed physical receipts cannot open another guest turn
     * or authorize context retirement. Identity verification and CAS
     * redemption are exercised concretely by HostGeneratorTurnPumpTest.
     */
    private enum ProofEvent {
        ISSUE, CLAIM, REPLAY, WRONG_CONTEXT, FOREIGN_ISSUER,
        PHYSICAL_EXIT, CANCEL_PUBLIC, SEAL, RETRY_CLOSE
    }

    private record ProofState(boolean issued, boolean claimed, boolean exited,
            boolean poisoned, boolean sealed, boolean retired, int claims) {
        static ProofState initial() {
            return new ProofState(false, false, false, false, false, false, 0);
        }
    }

    private static ProofState proofStep(ProofState s, ProofEvent e) {
        if (s.retired) return s;
        boolean issued = s.issued, claimed = s.claimed, exited = s.exited;
        boolean poisoned = s.poisoned, sealed = s.sealed, retired = false;
        int claims = s.claims;
        switch (e) {
            case ISSUE -> {
                if (!issued && !sealed) issued = true;
            }
            case CLAIM -> {
                if (issued && !claimed && !sealed) {
                    claimed = true;
                    claims++;
                }
            }
            case REPLAY -> {
                if (claimed) {
                    // Invalid second claim must not mint a new physical lease.
                    poisoned = true;
                    sealed = true;
                }
            }
            case WRONG_CONTEXT, FOREIGN_ISSUER -> {
                if (issued && !sealed) {
                    poisoned = true;
                    sealed = true;
                }
            }
            case PHYSICAL_EXIT -> {
                if (claimed) exited = true;
            }
            case CANCEL_PUBLIC -> {
                // Cancellation is not proof of TruffleContext.leave.
            }
            case SEAL -> sealed = true;
            case RETRY_CLOSE -> {
                if (sealed && (!claimed || exited) && !poisoned) retired = true;
            }
        }
        return new ProofState(
                issued, claimed, exited, poisoned, sealed, retired, claims);
    }

    private static void invariantProof(ProofState s) {
        assertTrue(s.claims >= 0 && s.claims <= 1,
                "one physical receipt authorized multiple generator pulls");
        if (s.retired) {
            assertTrue(s.sealed && !s.poisoned && (!s.claimed || s.exited),
                    "retirement occurred without verified physical DONE");
        }
        if (s.poisoned) {
            assertTrue(s.sealed && !s.retired,
                    "forged, foreign or replayed receipt escaped quarantine");
        }
    }

    @Test
    void allBoundedPhysicalCertificateEventsPreserveOneUseRetirementSafety() {
        Set<ProofState> visited = new HashSet<>();
        ArrayDeque<ProofState> frontier = new ArrayDeque<>();
        visited.add(ProofState.initial());
        frontier.add(ProofState.initial());
        boolean sawReplay = false, sawForeign = false, sawRetired = false;
        while (!frontier.isEmpty()) {
            ProofState current = frontier.removeFirst();
            invariantProof(current);
            sawReplay |= current.claimed && current.poisoned;
            sawForeign |= current.issued && !current.claimed && current.poisoned;
            sawRetired |= current.retired;
            for (ProofEvent event : ProofEvent.values()) {
                ProofState next = proofStep(current, event);
                invariantProof(next);
                if (visited.add(next)) frontier.addLast(next);
            }
        }
        assertTrue(visited.size() >= 12, "physical receipt model lost race states");
        assertTrue(sawReplay && sawForeign && sawRetired,
                "physical receipt model missed replay, foreign and retirement paths");
    }

    @Test
    void deliberatelyUnsafeReceiptReplayIsRejectedByProofInvariant() {
        ProofState unsafe = new ProofState(true, true, true, false, true, false, 2);
        assertThrows(AssertionError.class, () -> invariantProof(unsafe),
                "model mutation should expose an unsafe second receipt redemption");
    }

    @Test
    void publicCancellationNeverReleasesThePhysicalExitBarrier() {
        State state = State.initial();
        state = step(state, Event.ENQUEUE);
        state = step(state, Event.OWNER_DISPATCH);
        state = step(state, Event.CANCEL_ACTIVE);
        state = step(state, Event.SEAL_CONTEXT);
        assertTrue(state.resultReady && state.active);
        assertFalse(state.physicalExit);
        state = step(state, Event.PUBLISH_AFTER_EXIT);
        assertTrue(state.active);
        state = step(state, Event.RETRY_CLOSE);
        assertFalse(state.retired);
        state = step(state, Event.PHYSICAL_EXIT);
        state = step(state, Event.PUBLISH_AFTER_EXIT);
        state = step(state, Event.RETRY_CLOSE);
        assertTrue(state.retired);
        invariant(state);
    }

    /**
     * Frame-local lease abstraction independent of the host queue. A compiler
     * bug or internal caller bypassing the pump still cannot resume the same
     * heap-resident source continuation in two live physical turns.
     *
     * This is bounded model checking, not a Truffle refinement proof.
     */
    private enum FrameEvent {
        START, RESULT_SETTLED, CANCEL_PUBLIC, PHYSICAL_DONE,
        PHYSICAL_FAILED, STARTER_FAILED_NO_RECEIPT
    }

    private record FrameState(
            int totalStarts, int physicalOwners, boolean leased,
            boolean publicSettled, boolean poisoned) {
        static FrameState initial() {
            return new FrameState(0, 0, false, false, false);
        }
    }

    private static FrameState frameStep(FrameState s, FrameEvent event) {
        int starts = s.totalStarts, physical = s.physicalOwners;
        boolean leased = s.leased, settled = s.publicSettled;
        boolean poisoned = s.poisoned;
        switch (event) {
            case START -> {
                if (!poisoned && !leased && starts < 2) {
                    starts++;
                    physical++;
                    leased = true;
                    settled = false;
                }
            }
            case RESULT_SETTLED, CANCEL_PUBLIC -> {
                if (leased) settled = true;
            }
            case PHYSICAL_DONE -> {
                if (leased && settled && !poisoned) {
                    physical--;
                    leased = false;
                    settled = false;
                }
            }
            case PHYSICAL_FAILED, STARTER_FAILED_NO_RECEIPT -> {
                if (leased) {
                    // Unknown carrier outcome keeps the physical owner
                    // conservatively reserved for the lifetime of incarnation.
                    poisoned = true;
                }
            }
        }
        return new FrameState(starts, physical, leased, settled, poisoned);
    }

    private static void invariantFrame(FrameState state) {
        assertTrue(state.physicalOwners >= 0 && state.physicalOwners <= 1,
                "two generator source frames concurrently own physical execution");
        assertTrue(state.totalStarts >= 0 && state.totalStarts <= 2);
        assertEquals(state.physicalOwners == 1, state.leased,
                "frame lease lost physical turn ownership");
        if (state.poisoned) {
            assertTrue(state.leased,
                    "unproven guest exit improperly released a heap continuation");
        }
    }

    @Test
    void allBoundedFrameInterleavingsRequirePhysicalExitBeforeReuse() {
        Set<FrameState> visited = new HashSet<>();
        ArrayDeque<FrameState> frontier = new ArrayDeque<>();
        FrameState initial = FrameState.initial();
        visited.add(initial);
        frontier.add(initial);
        boolean sawCancelledOwner = false, sawQuarantine = false;
        boolean sawTwoSequentialStarts = false;
        while (!frontier.isEmpty()) {
            FrameState current = frontier.removeFirst();
            invariantFrame(current);
            sawCancelledOwner |= current.leased && current.publicSettled;
            sawQuarantine |= current.poisoned;
            sawTwoSequentialStarts |= current.totalStarts == 2;
            for (FrameEvent event : FrameEvent.values()) {
                FrameState next = frameStep(current, event);
                invariantFrame(next);
                if (event == FrameEvent.START && current.leased) {
                    assertEquals(current, next,
                            "an overlapping physical frame start was accepted");
                }
                if (current.poisoned && event == FrameEvent.START) {
                    assertEquals(current, next,
                            "unknown physical exit must permanently reject new starts");
                }
                if (visited.add(next)) frontier.addLast(next);
            }
        }
        assertTrue(sawCancelledOwner && sawQuarantine && sawTwoSequentialStarts,
                "frame model did not cover cancellation, unknown exit and reuse");
    }

    @Test
    void deliberatelyUnsafeFrameReentryViolatesPhysicalOwnerInvariant() {
        FrameState overlapping = new FrameState(2, 2, true, true, false);
        assertThrows(AssertionError.class, () -> invariantFrame(overlapping));
    }

    /**
     * Exhaustive finite-state abstraction of the *admitted whole-body* source
     * try/empty-catch/literal-finally generator. It does not prove Truffle
     * implementation refinement; concrete HostGeneratorTurnPumpTest checks
     * source AST lowering and scheduler cancellation on the actual runtime.
     */
    private enum LexicalEvent {
        FIRST_YIELD, BEGIN_AWAIT, AWAIT_SUCCEEDED, AWAIT_REJECTED,
        CANCEL_PUBLIC, NEXT_PULL, RETIRE
    }

    private record LexicalState(int phase, int cleanupRuns, boolean caughtError,
            boolean cancelled, boolean retired) {
        static LexicalState initial() { return new LexicalState(0, 0, false, false, false); }
    }

    private static LexicalState lexicalStep(LexicalState s, LexicalEvent e) {
        if (s.retired || s.phase == 4 && e != LexicalEvent.RETIRE) return s;
        int phase = s.phase;
        int cleanup = s.cleanupRuns;
        boolean caught = s.caughtError, cancelled = s.cancelled, retired = s.retired;
        switch (e) {
            case FIRST_YIELD -> {
                if (phase == 0) phase = 1;
            }
            case BEGIN_AWAIT -> {
                if (phase == 1) phase = 2;
            }
            case AWAIT_SUCCEEDED -> {
                if (phase == 2) phase = 3;
            }
            case AWAIT_REJECTED -> {
                if (phase == 2) {
                    // Empty catch consumes rejection, and finally runs.
                    phase = 4;
                    caught = true;
                    cleanup++;
                }
            }
            case CANCEL_PUBLIC -> {
                if (phase != 4) {
                    phase = 4;
                    cancelled = true;
                    cleanup++;
                }
            }
            case NEXT_PULL -> {
                if (phase == 3) {
                    phase = 4;
                    cleanup++;
                }
            }
            case RETIRE -> {
                if (phase == 4 && cleanup == 1) retired = true;
            }
        }
        return new LexicalState(phase, cleanup, caught, cancelled, retired);
    }

    private static void invariantLexical(LexicalState s) {
        assertTrue(s.phase >= 0 && s.phase <= 4);
        assertTrue(s.cleanupRuns >= 0 && s.cleanupRuns <= 1,
                "finally was executed twice");
        if (s.phase < 4) assertEquals(0, s.cleanupRuns,
                "finally escaped lexical scope before generator exit");
        if (s.phase == 4) assertEquals(1, s.cleanupRuns,
                "generator terminated without finally");
        if (s.caughtError) assertTrue(s.phase == 4 && !s.cancelled,
                "empty catch must terminate after consuming await rejection");
        if (s.retired) assertTrue(s.phase == 4 && s.cleanupRuns == 1,
                "physical retirement preceded source cleanup");
    }

    @Test
    void boundedWholeBodyGeneratorCatchFinallyPreservesExitInvariants() {
        Set<LexicalState> visited = new HashSet<>();
        ArrayDeque<LexicalState> queue = new ArrayDeque<>();
        LexicalState initial = LexicalState.initial();
        visited.add(initial);
        queue.add(initial);
        boolean sawCaught = false, sawCancellation = false, sawNormal = false;
        while (!queue.isEmpty()) {
            LexicalState state = queue.removeFirst();
            invariantLexical(state);
            sawCaught |= state.caughtError;
            sawCancellation |= state.cancelled;
            sawNormal |= state.retired && !state.caughtError && !state.cancelled;
            for (LexicalEvent event : LexicalEvent.values()) {
                LexicalState next = lexicalStep(state, event);
                invariantLexical(next);
                if (visited.add(next)) queue.addLast(next);
            }
        }
        assertTrue(visited.size() >= 9, "lexical model exploration collapsed");
        assertTrue(sawCaught && sawCancellation && sawNormal,
                "missed caught rejection, cancellation or normal retirement");
    }

    @Test
    void unsafeEarlyOrMissingFinallyFailsLexicalInvariant() {
        assertThrows(AssertionError.class, () ->
                invariantLexical(new LexicalState(1, 1, false, false, false)));
        assertThrows(AssertionError.class, () ->
                invariantLexical(new LexicalState(4, 0, true, false, true)));
    }


    /** Bounded abstraction of the staged Java nextStep and close contract. */
    private enum HostSessionEvent {
        NEXT, YIELD, DONE, PHYSICAL_DONE, CANCEL_PUBLIC,
        UNKNOWN_PHYSICAL_EXIT, SEAL, RETRY_CLOSE
    }

    private record HostSessionState(
            int pulls, boolean physicalActive, boolean yielded,
            boolean terminal, boolean sealed, boolean poisoned,
            boolean retired) {
        static HostSessionState initial() {
            return new HostSessionState(0, false, false, false,
                    false, false, false);
        }
    }

    private static HostSessionState hostSessionStep(
            HostSessionState s, HostSessionEvent event) {
        if (s.retired) return s;
        int pulls = s.pulls;
        boolean physical = s.physicalActive;
        boolean yielded = s.yielded, terminal = s.terminal;
        boolean sealed = s.sealed, poisoned = s.poisoned, retired = false;
        switch (event) {
            case NEXT -> {
                if (!sealed && !physical && pulls < 2) {
                    pulls++;
                    physical = true;
                }
            }
            case YIELD -> {
                if (physical && !terminal) yielded = true;
            }
            case DONE -> {
                if (physical) terminal = true;
            }
            case PHYSICAL_DONE -> {
                if (physical && !poisoned) physical = false;
            }
            case CANCEL_PUBLIC -> {
                // Cancellation of a host Future is not physical DONE nor
                // proof that lexical source cleanup has completed.
            }
            case UNKNOWN_PHYSICAL_EXIT -> {
                if (physical) {
                    poisoned = true;
                    sealed = true;
                }
            }
            case SEAL, RETRY_CLOSE -> {
                sealed = true;
                // An untouched session may close, but a started source frame
                // must be terminal AND physically quiescent before retirement.
                if (!physical && !poisoned && (pulls == 0 || terminal)) {
                    retired = true;
                }
            }
        }
        return new HostSessionState(pulls, physical, yielded, terminal,
                sealed, poisoned, retired);
    }

    private static void invariantHostSession(HostSessionState state) {
        assertTrue(state.pulls >= 0 && state.pulls <= 2);
        if (state.poisoned) {
            assertTrue(state.sealed && !state.retired,
                    "unknown guest exit released an unproven context");
        }
        if (state.retired) {
            assertTrue(state.sealed && !state.physicalActive && !state.poisoned
                    && (state.pulls == 0 || state.terminal),
                    "host session retired an unexhausted generator");
        }
    }

    @Test
    void boundedJavaHostNextStepCannotRetireUnexhaustedOrActiveSource() {
        Set<HostSessionState> states = new HashSet<>();
        ArrayDeque<HostSessionState> open = new ArrayDeque<>();
        HostSessionState initial = HostSessionState.initial();
        states.add(initial);
        open.add(initial);
        boolean sawIdleYieldClose = false, sawCompletedRetirement = false;
        boolean sawUnknownQuarantine = false;
        while (!open.isEmpty()) {
            HostSessionState state = open.removeFirst();
            invariantHostSession(state);
            sawUnknownQuarantine |= state.poisoned;
            sawCompletedRetirement |= state.retired && state.pulls != 0;
            for (HostSessionEvent event : HostSessionEvent.values()) {
                HostSessionState next = hostSessionStep(state, event);
                invariantHostSession(next);
                if ((event == HostSessionEvent.SEAL
                        || event == HostSessionEvent.RETRY_CLOSE)
                        && state.yielded && !state.physicalActive
                        && !state.terminal) {
                    sawIdleYieldClose = true;
                    assertFalse(next.retired,
                            "close must not treat a yielded source as cleaned up");
                }
                if (states.add(next)) open.addLast(next);
            }
        }
        assertTrue(states.size() >= 12, "session state exploration collapsed");
        assertTrue(sawIdleYieldClose && sawCompletedRetirement
                && sawUnknownQuarantine,
                "missing early close, terminal retirement or unknown-exit race");
    }

    @Test
    void unsafeHostSessionRetirementWithoutCleanupViolatesInvariant() {
        HostSessionState unsafe =
                new HostSessionState(1, false, true, false, true, false, true);
        assertThrows(AssertionError.class, () -> invariantHostSession(unsafe));
    }

}
