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

    /**
     * Separate bounded abstraction of the physical entry lease. A failed
     * enter/leave permanently poisons the incarnation even if JVM-side lease
     * accounting returns to zero; close is a seal, not a proof of exit.
     */
    private enum PhysicalEvent {
        RESERVE, RELEASE, ENTER_UNKNOWN, LEAVE_UNKNOWN, CLOSE
    }

    private record PhysicalState(int leases, boolean poisoned, boolean closed) {
        static PhysicalState initial() {
            return new PhysicalState(0, false, false);
        }
        boolean mayRetire() {
            return leases == 0 && !poisoned;
        }
    }

    private static PhysicalState physicalStep(PhysicalState s, PhysicalEvent e) {
        return switch (e) {
            case RESERVE -> s.closed || s.poisoned || s.leases == 2
                    ? null : new PhysicalState(s.leases + 1, false, false);
            case RELEASE -> s.leases == 0
                    ? null : new PhysicalState(s.leases - 1, s.poisoned, s.closed);
            case ENTER_UNKNOWN, LEAVE_UNKNOWN -> s.leases == 0
                    ? null : new PhysicalState(s.leases, true, true);
            case CLOSE -> s.closed ? null : new PhysicalState(s.leases, s.poisoned, true);
        };
    }

    @Test
    void exhaustivePhysicalEnterLeaveAndCloseNeverClearUnknownGuestState() {
        Set<PhysicalState> seen = new HashSet<>();
        ArrayDeque<PhysicalState> pending = new ArrayDeque<>();
        seen.add(PhysicalState.initial());
        pending.add(PhysicalState.initial());
        boolean sawUnknownDrained = false;
        boolean sawCleanDrained = false;
        while (!pending.isEmpty()) {
            PhysicalState s = pending.removeFirst();
            assertTrue(s.leases >= 0 && s.leases <= 2);
            if (s.poisoned) {
                assertTrue(s.closed, "unknown physical entry must seal admission");
                assertFalse(s.mayRetire(), "zero leases cannot certify unknown entry");
            }
            sawUnknownDrained |= s.poisoned && s.leases == 0;
            sawCleanDrained |= s.closed && s.leases == 0 && !s.poisoned;
            for (PhysicalEvent event : PhysicalEvent.values()) {
                PhysicalState next = physicalStep(s, event);
                if (next == null) continue;
                if (s.poisoned) assertTrue(next.poisoned,
                        "a close, release, or retry may not clear quarantine");
                if (s.closed) assertTrue(next.closed,
                        "no reopening admission after close/quarantine");
                if (seen.add(next)) pending.addLast(next);
            }
        }
        assertTrue(sawUnknownDrained);
        assertTrue(sawCleanDrained);
        assertTrue(seen.size() >= 8);
    }

    @Test
    void leaseCountOnlyRetirementHasConcreteUnknownEntryCounterexample() {
        PhysicalState started = physicalStep(
                PhysicalState.initial(), PhysicalEvent.RESERVE);
        PhysicalState unknown = physicalStep(started, PhysicalEvent.ENTER_UNKNOWN);
        PhysicalState released = physicalStep(unknown, PhysicalEvent.RELEASE);
        assertEquals(0, released.leases);
        assertFalse(released.mayRetire());
        // This is the rejected implementation: a successful JVM decrement
        // alone would count the unknown Truffle enter as physical DONE.
        assertThrows(AssertionError.class, () ->
                assertFalse(released.leases == 0));
    }

    private enum OwnedEvent {
        OWN_ENTER, OWN_LEAVE, FOREIGN_ENTER, FOREIGN_LEAVE,
        HOST_BEGIN, HOST_END, SEAL
    }

    private record OwnedState(int own, int foreign, int hostDepth, boolean sealed) {
        static OwnedState initial() { return new OwnedState(0, 0, 0, false); }
    }

    private static OwnedState nextOwned(OwnedState s, OwnedEvent e) {
        return switch (e) {
            case OWN_ENTER -> s.sealed || s.hostDepth > 0 || s.own == 2
                    ? null : new OwnedState(s.own + 1, s.foreign, s.hostDepth, false);
            case OWN_LEAVE -> s.own == 0 ? null
                    : new OwnedState(s.own - 1, s.foreign, s.hostDepth, s.sealed);
            case FOREIGN_ENTER -> s.sealed || s.hostDepth > 0 || s.foreign == 2
                    ? null : new OwnedState(s.own, s.foreign + 1, s.hostDepth, false);
            case FOREIGN_LEAVE -> s.foreign == 0 ? null
                    : new OwnedState(s.own, s.foreign - 1, s.hostDepth, s.sealed);
            case HOST_BEGIN -> s.sealed || s.foreign > 0 || s.hostDepth == 2
                    ? null : new OwnedState(s.own, s.foreign, s.hostDepth + 1, false);
            case HOST_END -> s.hostDepth == 0 ? null
                    : new OwnedState(s.own, s.foreign, s.hostDepth - 1, s.sealed);
            case SEAL -> s.sealed ? null
                    : new OwnedState(s.own, s.foreign, s.hostDepth, true);
        };
    }

    private static void ownedInvariant(OwnedState s) {
        assertTrue(s.own >= 0 && s.own <= 2);
        assertTrue(s.foreign >= 0 && s.foreign <= 2);
        assertTrue(s.hostDepth >= 0 && s.hostDepth <= 2);
        assertFalse(s.hostDepth > 0 && s.foreign > 0,
                "host reentry cannot launder another carrier's guest lease");
    }

    @Test
    void boundedSameCarrierReentryAndForeignOverlapAllOrders() {
        java.util.Set<OwnedState> explored = new java.util.HashSet<>();
        java.util.ArrayDeque<OwnedState> pending = new java.util.ArrayDeque<>();
        pending.add(OwnedState.initial());
        explored.add(OwnedState.initial());
        boolean sawLegitimateReentry = false;
        boolean sawForeignOverlapWithoutHost = false;
        boolean sawSealedCleanup = false;
        while (!pending.isEmpty()) {
            OwnedState current = pending.removeFirst();
            ownedInvariant(current);
            sawLegitimateReentry |= current.own > 0 && current.hostDepth > 0;
            sawForeignOverlapWithoutHost |= current.own > 0
                    && current.foreign > 0 && current.hostDepth == 0;
            sawSealedCleanup |= current.sealed && current.own > 0;
            for (OwnedEvent e : OwnedEvent.values()) {
                OwnedState next = nextOwned(current, e);
                if (next != null && explored.add(next)) pending.addLast(next);
            }
        }
        assertTrue(sawLegitimateReentry);
        assertTrue(sawForeignOverlapWithoutHost);
        assertTrue(sawSealedCleanup);
        assertTrue(explored.size() >= 15);
    }

    @Test
    void unsafeForeignCarrierLaunderingProducesCounterexample() {
        OwnedState invalid = new OwnedState(1, 1, 1, false);
        assertThrows(AssertionError.class, () -> ownedInvariant(invalid));
    }


}
