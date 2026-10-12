package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A bounded state-space model for two buffered select arms and one source
 * cancellation. The runtime coordinator must refine these transitions; the
 * model alone is NOT a proof of the Java/Truffle implementation.
 *
 * Actions: register, send(channel 0|1), cancel, retire, staleCallback.
 * A send and a receive commit atomically at the select linearization point.
 */
final class FormalSelectCancellationOrderingModelCheckTest {
    private record State(boolean registered, boolean registerAttempted,
                         boolean cancelled, boolean retired, boolean staleCallback,
                         int selected, int sent, int buffered, int consumed) {
        static State initial() {
            return new State(false, false, false, false, false, -1, 0, 0, 0);
        }

        boolean terminal() { return cancelled || selected >= 0; }
    }

    private static State commit(State s, int arm, int sent, int buffered) {
        int bit = 1 << arm;
        return new State(false, s.registerAttempted, false, s.retired,
                s.staleCallback, arm, sent, buffered & ~bit, s.consumed | bit);
    }

    private static State register(State s) {
        if (s.registerAttempted || s.retired) return null;
        State staged = new State(!s.cancelled, true, s.cancelled, false,
                s.staleCallback, s.selected, s.sent, s.buffered, s.consumed);
        if (s.cancelled) return staged; // cancellation fences late registration
        if ((s.buffered & 1) != 0) return commit(staged, 0, s.sent, s.buffered);
        if ((s.buffered & 2) != 0) return commit(staged, 1, s.sent, s.buffered);
        return staged;
    }

    private static State send(State s, int arm) {
        int bit = 1 << arm;
        // Actor retirement revokes this receiver's registration, NOT
        // channel sends issued independently by surviving producers.
        if ((s.sent & bit) != 0) return null;
        int nextSent = s.sent | bit;
        if (s.registered && !s.terminal()) {
            return commit(s, arm, nextSent, s.buffered);
        }
        return new State(s.registered, s.registerAttempted, s.cancelled,
                s.retired, s.staleCallback, s.selected, nextSent,
                s.buffered | bit, s.consumed);
    }

    private static State cancel(State s) {
        if (s.terminal() || s.retired) return null;
        return new State(false, s.registerAttempted, true, false,
                s.staleCallback, -1, s.sent, s.buffered, s.consumed);
    }

    private static State retire(State s) {
        if (s.retired) return null;
        // Source actor retirement is an atomic coordinator barrier. If it
        // beats a channel commit, retirement cancels the pending registration.
        // If physical commit won earlier, its delivered value is not revoked.
        boolean cancelled = s.cancelled || s.selected < 0;
        return new State(false, s.registerAttempted, cancelled, true,
                s.staleCallback, s.selected, s.sent, s.buffered, s.consumed);
    }

    private static State lateCallback(State s) {
        if (!s.terminal() || s.staleCallback) return null;
        return new State(s.registered, s.registerAttempted, s.cancelled,
                s.retired, true, s.selected, s.sent, s.buffered, s.consumed);
    }

    private static void assertSafety(State s) {
        assertEquals(s.sent, s.buffered | s.consumed, "channel value conservation");
        assertEquals(0, s.buffered & s.consumed,
                "value cannot be both buffered and committed");
        assertTrue(Integer.bitCount(s.consumed) <= 1,
                "a two-arm select can commit at most once");
        if (s.cancelled) {
            assertEquals(0, s.consumed, "cancel won: no future channel effect");
        }
        if (s.selected >= 0) {
            assertEquals(1 << s.selected, s.consumed,
                    "exactly one selected arm owns the delivered value");
        } else {
            assertEquals(0, s.consumed, "unselected wait consumed a message");
        }
        if (s.terminal() || s.retired) {
            assertFalse(s.registered,
                    "cancel/commit/retirement must detach all pending arms");
        }
        if (s.retired && s.selected < 0) {
            assertTrue(s.cancelled,
                    "retirement of a pending source selection must settle cancellation");
        }
    }

    private static void enqueue(Set<State> seen, ArrayDeque<State> work, State s) {
        if (s != null && seen.add(s)) work.addLast(s);
    }

    @Test
    void exhaustiveTwoArmRegisterCancelProducerRetirementSchedules() {
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> work = new ArrayDeque<>();
        enqueue(seen, work, State.initial());
        boolean cancelledBeforeRegistration = false;
        boolean committedBeforeCancellation = false;
        boolean selectedSecondArm = false;
        boolean retiredAfterCancellation = false;
        boolean retiredWhilePending = false;
        boolean producerAfterRetirement = false;
        boolean committedBeforeRetirement = false;

        while (!work.isEmpty()) {
            State s = work.removeFirst();
            assertSafety(s);
            cancelledBeforeRegistration |= s.cancelled && !s.registerAttempted;
            committedBeforeCancellation |= s.selected >= 0 && !s.cancelled;
            selectedSecondArm |= s.selected == 1;
            retiredAfterCancellation |= s.cancelled && s.retired;
            retiredWhilePending |= s.registerAttempted && s.cancelled && s.retired
                    && s.selected < 0;
            producerAfterRetirement |= s.retired && s.sent != 0 && s.consumed == 0
                    && s.buffered != 0;
            committedBeforeRetirement |= s.retired && s.selected >= 0;
            enqueue(seen, work, register(s));
            enqueue(seen, work, send(s, 0));
            enqueue(seen, work, send(s, 1));
            enqueue(seen, work, cancel(s));
            enqueue(seen, work, retire(s));
            enqueue(seen, work, lateCallback(s));
            assertTrue(seen.size() < 10_000, "state space unexpectedly unbounded");
        }

        assertTrue(cancelledBeforeRegistration);
        assertTrue(committedBeforeCancellation);
        assertTrue(selectedSecondArm);
        assertTrue(retiredAfterCancellation);
        assertTrue(retiredWhilePending);
        assertTrue(producerAfterRetirement);
        assertTrue(committedBeforeRetirement);
        assertTrue(seen.size() >= 20, "model must explore nontrivial interleavings");
    }

    @Test
    void intentionallyUnsafeRetirementWithoutDetachmentProducesCounterexample() {
        State unsafe = new State(true, true, false, true, false,
                -1, 0, 0, 0);
        assertThrows(AssertionError.class, () -> assertSafety(unsafe));
    }

    @Test
    void intentionallyUnsafePostRetirementConsumeProducesCounterexample() {
        State unsafe = new State(false, true, true, true, false,
                0, 1, 0, 1);
        assertThrows(AssertionError.class, () -> assertSafety(unsafe));
    }

    @Test
    void intentionallyUnsafeLateRegistrationProducesCounterexample() {
        // If cancellation could be followed by stale registration AND a
        // message commit, the safety invariant immediately catches it.
        State unsafe = new State(false, true, true, false, false,
                0, 1, 0, 1);
        assertThrows(AssertionError.class, () -> assertSafety(unsafe));
    }
}
