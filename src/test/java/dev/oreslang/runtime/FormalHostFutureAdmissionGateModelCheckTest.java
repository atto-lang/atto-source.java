package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finite-state abstraction of a scoped host Future claim and guest Truffle
 * reservations. Live HostFutureAdmissionGateTest tests the Java monitor.
 * This model does not claim to verify foreign Polyglot Value.execute calls.
 */
final class FormalHostFutureAdmissionGateModelCheckTest {
    private enum Event {
        HOST_BEGIN, HOST_END, GUEST_RESERVE, GUEST_LEAVE, CONTEXT_DISPOSE
    }

    private record State(int hostDepth, int guestLeases, boolean disposed) {
        static State initial() { return new State(0, 0, false); }
    }

    private static State transition(State s, Event event) {
        return switch (event) {
            case HOST_BEGIN -> (s.disposed || s.guestLeases != 0 || s.hostDepth == 2)
                    ? null : new State(s.hostDepth + 1, s.guestLeases, false);
            case HOST_END -> s.hostDepth == 0
                    ? null : new State(s.hostDepth - 1, s.guestLeases, s.disposed);
            case GUEST_RESERVE -> (s.disposed || s.hostDepth != 0 || s.guestLeases == 2)
                    ? null : new State(s.hostDepth, s.guestLeases + 1, false);
            case GUEST_LEAVE -> s.guestLeases == 0
                    ? null : new State(s.hostDepth, s.guestLeases - 1, s.disposed);
            case CONTEXT_DISPOSE -> s.disposed
                    ? null : new State(s.hostDepth, s.guestLeases, true);
        };
    }

    private static void invariant(State s) {
        assertTrue(s.hostDepth >= 0 && s.hostDepth <= 2,
                "invalid host claim depth");
        assertTrue(s.guestLeases >= 0 && s.guestLeases <= 2,
                "guest entry was released twice or over-reserved");
        assertFalse(s.hostDepth > 0 && s.guestLeases > 0,
                "a scoped host Future handoff overlapped reserved guest entry");
    }

    @Test
    void boundedAllHostGuestAndDisposalOrdersPreserveExclusion() {
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> pending = new ArrayDeque<>();
        seen.add(State.initial());
        pending.add(State.initial());
        boolean sawNested = false;
        boolean sawParallelGuest = false;
        boolean sawDisposeWithLiveScope = false;
        boolean sawDisposeWithLiveGuest = false;

        while (!pending.isEmpty()) {
            State s = pending.removeFirst();
            invariant(s);
            sawNested |= s.hostDepth == 2;
            sawParallelGuest |= s.guestLeases == 2;
            sawDisposeWithLiveScope |= s.disposed && s.hostDepth != 0;
            sawDisposeWithLiveGuest |= s.disposed && s.guestLeases != 0;
            for (Event event : Event.values()) {
                State next = transition(s, event);
                if (next != null && seen.add(next)) pending.addLast(next);
            }
        }

        assertTrue(sawNested);
        assertTrue(sawParallelGuest);
        assertTrue(sawDisposeWithLiveScope);
        assertTrue(sawDisposeWithLiveGuest);
        assertTrue(seen.size() >= 10, "model must explore meaningful interleavings");
    }

    @Test
    void unsafeWaitThenEnterWithoutReservationHasExpectedCounterexample() {
        State unsafe = new State(1, 1, false);
        assertThrows(AssertionError.class, () -> invariant(unsafe));
    }

    @Test
    void unsafeDoubleGuestReleaseHasExpectedCounterexample() {
        State unsafe = new State(0, -1, false);
        assertThrows(AssertionError.class, () -> invariant(unsafe));
    }

    @Test
    void disposedContextRefusesFreshClaimsButAllowsPriorLeasesToLeave() {
        State disposed = new State(1, 0, true);
        assertNull(transition(disposed, Event.HOST_BEGIN));
        assertNull(transition(disposed, Event.GUEST_RESERVE));
        State released = transition(disposed, Event.HOST_END);
        assertNotNull(released);
        assertEquals(0, released.hostDepth());
        invariant(released);
    }
}
