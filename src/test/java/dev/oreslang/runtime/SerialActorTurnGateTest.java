package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exhaustive serial admission / cancellation fence edge cases. */
final class SerialActorTurnGateTest {
    @Test void twoBarriersCannotAdmitNextMailUntilBothComplete() {
        var gate = new SerialActorTurnGate();
        Object first = new Object();
        Object second = new Object();
        gate.hold(first);
        gate.hold(first);
        assertTrue(gate.occupied());
        assertEquals(2, gate.pendingBarriers());
        assertThrows(IllegalStateException.class, () -> gate.hold(second));
        assertEquals(SerialActorTurnGate.Completion.HELD, gate.complete(first));
        assertTrue(gate.occupied());
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(second));
        assertEquals(SerialActorTurnGate.Completion.RELEASED, gate.complete(first));
        assertFalse(gate.occupied());
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(first));
        gate.hold(second);
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(first));
        assertTrue(gate.occupied());
        assertEquals(SerialActorTurnGate.Completion.RELEASED, gate.complete(second));
    }

    @Test void cancellationAndRestartNeverAuthorizeOldCompletion() {
        var gate = new SerialActorTurnGate();
        Object oldMail = new Object();
        Object freshMail = new Object();
        gate.hold(oldMail);
        gate.hold(oldMail);
        gate.retire();
        assertFalse(gate.occupied());
        gate.hold(freshMail);
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(oldMail));
        assertEquals(1, gate.pendingBarriers());
        assertEquals(SerialActorTurnGate.Completion.RELEASED, gate.complete(freshMail));
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(freshMail));
    }

    @Test void boundedBarrierIdentityAndNullRejections() {
        var gate = new SerialActorTurnGate();
        assertThrows(NullPointerException.class, () -> gate.hold(null));
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(null));
        Object sameEqualValueA = new String("same-mail");
        Object sameEqualValueB = new String("same-mail");
        gate.hold(sameEqualValueA);
        assertThrows(IllegalStateException.class, () -> gate.hold(sameEqualValueB));
        assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(sameEqualValueB));
        assertEquals(SerialActorTurnGate.Completion.RELEASED, gate.complete(sameEqualValueA));
    }
}
