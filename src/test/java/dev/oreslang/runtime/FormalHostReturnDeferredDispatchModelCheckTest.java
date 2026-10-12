package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive bounded abstraction of the two-phase host-return source-task
 * handoff. This checks the intended transition contract, NOT a proof that
 * every Truffle host/actor/async entry refines this model.
 */
final class FormalHostReturnDeferredDispatchModelCheckTest {
    private enum Event {
        PREPARE, PUBLISH, HOST_RETURN, DISPATCH, GUEST_ENTER,
        CANCEL_UNSTARTED, GUEST_EXIT, RETIRE, REPLAY_DISPATCH
    }

    private record State(
            boolean prepared, boolean published, boolean hostExited,
            boolean dispatched, boolean guestEntered, boolean guestActive,
            boolean guestFinished, boolean cancelled, boolean retired,
            int physicalDispatches) {
        static State initial() {
            return new State(false, false, false, false, false, false,
                    false, false, false, 0);
        }
    }

    private static State transition(State s, Event e) {
        boolean prepared = s.prepared, published = s.published;
        boolean exited = s.hostExited, dispatched = s.dispatched;
        boolean entered = s.guestEntered, active = s.guestActive;
        boolean finished = s.guestFinished, cancelled = s.cancelled;
        boolean retired = s.retired;
        int dispatches = s.physicalDispatches;
        switch (e) {
            case PREPARE -> {
                if (!prepared && !retired) prepared = true;
            }
            case PUBLISH -> {
                if (prepared && !cancelled && !retired) published = true;
            }
            case HOST_RETURN -> exited = true;
            case DISPATCH -> {
                if (published && exited && !dispatched && !cancelled && !retired) {
                    dispatched = true;
                    dispatches++;
                }
            }
            case GUEST_ENTER -> {
                if (dispatched && !entered && !cancelled && !retired) {
                    entered = true;
                    active = true;
                }
            }
            case CANCEL_UNSTARTED -> {
                if (prepared && !entered && !finished && !retired) cancelled = true;
            }
            case GUEST_EXIT -> {
                if (active) {
                    active = false;
                    finished = true;
                }
            }
            case RETIRE -> {
                if (exited && !active &&
                        (!prepared || cancelled || finished)) retired = true;
            }
            case REPLAY_DISPATCH -> { /* one-use ticket: always a no-op */ }
        }
        return new State(prepared, published, exited, dispatched, entered,
                active, finished, cancelled, retired, dispatches);
    }

    private static void invariant(State s) {
        assertTrue(s.physicalDispatches <= 1, "physical task dispatched twice");
        if (s.published) assertTrue(s.prepared,
                "Future published without source registration");
        if (s.dispatched) assertTrue(s.published && s.hostExited,
                "carrier published before the host Value.execute exit");
        if (s.guestEntered) assertTrue(s.dispatched && s.hostExited,
                "guest entered Truffle before its host call returned");
        if (s.guestActive) assertTrue(s.guestEntered && !s.guestFinished,
                "physical guest ownership fabricated after completion");
        if (s.retired) assertTrue(s.hostExited && !s.guestActive
                && (!s.prepared || s.cancelled || s.guestFinished),
                "context retired with pending physical source ownership");
        if (s.cancelled && !s.guestEntered) assertFalse(s.guestActive,
                "cancelled unstarted source acquired physical entry");
    }

    @Test
    void exhaustiveInterleavingsNeverStartBeforeHostReturn() {
        ArrayDeque<State> todo = new ArrayDeque<>();
        Set<State> seen = new HashSet<>();
        todo.add(State.initial());
        seen.add(State.initial());
        boolean sawCancellation = false, sawCompleted = false, sawRetirement = false;
        while (!todo.isEmpty()) {
            State s = todo.removeFirst();
            invariant(s);
            sawCancellation |= s.cancelled && !s.guestEntered;
            sawCompleted |= s.guestFinished;
            sawRetirement |= s.retired;
            for (Event event : Event.values()) {
                State next = transition(s, event);
                invariant(next);
                if (event == Event.REPLAY_DISPATCH) {
                    assertEquals(s, next, "replayed dispatch changed ownership");
                }
                if (seen.add(next)) todo.addLast(next);
            }
        }
        assertEquals(19, seen.size(),
                "bounded handoff state space unexpectedly changed");
        assertTrue(sawCancellation && sawCompleted && sawRetirement);
    }

    @Test
    void unsafeCarrierEntryBeforeHostExitHasConcreteCounterexample() {
        State claim = transition(State.initial(), Event.PREPARE);
        State published = transition(claim, Event.PUBLISH);
        assertFalse(published.hostExited);
        // Mutant implementation directly dispatches as soon as publication
        // occurs and then enters the guest, without the host-return fence.
        State unsafe = new State(true, true, false, true, true, true,
                false, false, false, 1);
        assertThrows(AssertionError.class, () -> invariant(unsafe));
        State safe = transition(published, Event.DISPATCH);
        assertFalse(safe.dispatched);
    }

    @Test
    void cancelledPreparedTaskCanRetireWithoutPhysicalDispatch() {
        State s = State.initial();
        for (Event event : new Event[] {
                Event.PREPARE, Event.PUBLISH, Event.CANCEL_UNSTARTED,
                Event.HOST_RETURN, Event.DISPATCH, Event.GUEST_ENTER, Event.RETIRE}) {
            s = transition(s, event);
            invariant(s);
        }
        assertTrue(s.cancelled && s.retired);
        assertEquals(0, s.physicalDispatches);
        assertFalse(s.guestEntered);
    }
}
