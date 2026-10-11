package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exhaustive serial admission, independent barriers, and cancellation fences. */
final class SerialActorTurnGateTest {
    @Test void twoBarriersCannotAdmitNextMailUntilBothComplete() {
        var gate = new SerialActorTurnGate();
        Object first = new Object();
        Object second = new Object();
        var method = gate.hold(first);
        var response = gate.hold(first);
        assertTrue(gate.occupied());
        assertEquals(2, gate.pendingBarriers());
        assertThrows(IllegalStateException.class, () -> gate.hold(second));
        assertEquals(SerialActorTurnGate.Completion.HELD, gate.complete(method));
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(method),
                "a duplicate method callback must not release the response barrier");
        assertTrue(gate.occupied());
        assertEquals(1, gate.pendingBarriers());
        assertEquals(SerialActorTurnGate.Completion.RELEASED, gate.complete(response));
        assertFalse(gate.occupied());
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(response));
        var next = gate.hold(second);
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(method));
        assertTrue(gate.occupied());
        assertEquals(SerialActorTurnGate.Completion.RELEASED, gate.complete(next));
    }

    @Test void reverseOrderAndForeignGateCannotReleaseTurn() {
        var gate = new SerialActorTurnGate();
        var foreign = new SerialActorTurnGate();
        Object mail = new Object();
        var a = gate.hold(mail);
        var b = gate.hold(mail);
        var elsewhere = foreign.hold(mail);
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(elsewhere));
        assertEquals(SerialActorTurnGate.Completion.HELD, gate.complete(b));
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(b));
        assertEquals(SerialActorTurnGate.Completion.RELEASED, gate.complete(a));
        assertEquals(SerialActorTurnGate.Completion.RELEASED, foreign.complete(elsewhere));
    }

    @Test void cancellationAndRestartNeverAuthorizeOldCompletionEvenForReusedMail() {
        var gate = new SerialActorTurnGate();
        Object reusedMail = new Object();
        var oldOne = gate.hold(reusedMail);
        var oldTwo = gate.hold(reusedMail);
        gate.retire();
        assertFalse(gate.occupied());
        var fresh = gate.hold(reusedMail);
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(oldOne));
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(oldTwo));
        assertEquals(1, gate.pendingBarriers());
        assertEquals(SerialActorTurnGate.Completion.RELEASED, gate.complete(fresh));
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(fresh));
    }

    @Test void identityNullAndBoundedChurn() {
        var gate = new SerialActorTurnGate();
        assertThrows(NullPointerException.class, () -> gate.hold(null));
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(null));
        Object sameEqualValueA = new String("same-mail");
        Object sameEqualValueB = new String("same-mail");
        var first = gate.hold(sameEqualValueA);
        assertThrows(IllegalStateException.class, () -> gate.hold(sameEqualValueB));
        assertEquals(SerialActorTurnGate.Completion.RELEASED, gate.complete(first));
        for (int i = 0; i < 20_000; i++) {
            var ticket = gate.hold(sameEqualValueA);
            assertEquals(SerialActorTurnGate.Completion.RELEASED, gate.complete(ticket));
            assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(ticket));
        }
        assertFalse(gate.occupied());
        assertEquals(0, gate.pendingBarriers());
    }
}
