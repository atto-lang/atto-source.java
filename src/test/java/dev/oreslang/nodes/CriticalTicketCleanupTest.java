package dev.oreslang.nodes;

import dev.oreslang.runtime.CriticalGate;
import dev.oreslang.runtime.OresFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the exact SourceTask orphan-admission cleanup helper. */
final class CriticalTicketCleanupTest {
    @Test void immediateTicketIsReleasedAfterFailedSchedulerTransition() {
        CriticalGate gate = new CriticalGate();
        OresFuture<CriticalGate.Ticket> admitted = gate.requestAwaitable();
        assertTrue(admitted.isDone());
        assertTrue(gate.holds(admitted.join()));
        CriticalTicketCleanup.revoke(gate, admitted);
        assertTrue(gate.quiescent());
        assertTrue(gate.retireIfQuiescent());
    }

    @Test void queuedTicketIsRetractedWithoutReleasingAnotherOwner() {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket owner = gate.requestAwaitable().join();
        OresFuture<CriticalGate.Ticket> queued = gate.requestAwaitable();
        assertEquals(1, gate.waiting());
        CriticalTicketCleanup.revoke(gate, queued);
        assertTrue(queued.isCancelled());
        assertEquals(0, gate.waiting());
        assertTrue(gate.holds(owner));
        gate.release(owner);
        assertTrue(gate.quiescent());
    }

    @Test void retiredAdmissionIsSafeToUnwind() {
        CriticalGate gate = new CriticalGate();
        gate.requestAwaitable().join();
        OresFuture<CriticalGate.Ticket> pending = gate.requestAwaitable();
        gate.retire();
        assertDoesNotThrow(() -> CriticalTicketCleanup.revoke(gate, pending));
        assertTrue(gate.quiescent());
        assertTrue(gate.retired());
    }

    @Test
    @Timeout(20)
    void cancellationRacingWithPromotionCannotLeakActorOwnedTicket()
            throws Exception {
        for (int i = 0; i < 256; i++) {
            CriticalGate gate = new CriticalGate();
            CriticalGate.Ticket owner = gate.requestAwaitable().join();
            OresFuture<CriticalGate.Ticket> waiter = gate.requestAwaitable();
            Thread releasing = Thread.ofPlatform().start(() -> gate.release(owner));
            CriticalTicketCleanup.revoke(gate, waiter);
            releasing.join(3_000L);
            assertFalse(releasing.isAlive(), "release deadlocked");
            assertEquals(0, gate.waiting());
            assertTrue(gate.quiescent(), "ticket leaked at iteration " + i);
            assertTrue(gate.retireIfQuiescent());
        }
    }
}
