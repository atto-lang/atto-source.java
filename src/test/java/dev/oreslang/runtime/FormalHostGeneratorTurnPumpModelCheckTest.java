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
        SEAL_CONTEXT, PUBLISH_AFTER_EXIT, RETRY_CLOSE
    }

    private record State(
            int queued, int started, int published,
            boolean active, boolean resultReady, boolean physicalExit,
            boolean cancelled, boolean sealed, boolean retired) {
        static State initial() {
            return new State(0, 0, 0, false, false, false, false, false, false);
        }
    }

    private static State step(State s, Event event) {
        int queued = s.queued, started = s.started, published = s.published;
        boolean active = s.active, result = s.resultReady;
        boolean exited = s.physicalExit, cancelled = s.cancelled;
        boolean sealed = s.sealed, retired = s.retired;
        switch (event) {
            case ENQUEUE -> {
                if (!sealed && queued < 2) queued++;
            }
            case OWNER_DISPATCH -> {
                if (!sealed && !active && queued > 0) {
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
                if (sealed && queued == 0 && !active) retired = true;
            }
        }
        return new State(queued, started, published, active, result, exited,
                cancelled, sealed, retired);
    }

    private static void invariant(State s) {
        assertTrue(s.queued >= 0 && s.queued <= 2, "bounded FIFO capacity violated");
        assertTrue(s.started >= s.published,
                "publication precedes ownership");
        assertTrue(s.started - s.published <= 1,
                "two guest turns were admitted before the first published");
        if (!s.active) {
            assertFalse(s.resultReady || s.physicalExit || s.cancelled,
                    "inactive activation retained source-task state");
        }
        if (s.retired) {
            assertTrue(s.sealed && s.queued == 0 && !s.active,
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
}
