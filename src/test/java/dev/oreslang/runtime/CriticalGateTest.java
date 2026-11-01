package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host runtime evidence only. Guest lowering, transitive effect checking,
 * cancellation fencing, and scheduler dispatch remain separate acceptance gates.
 */
final class CriticalGateTest {
    @Test void immediateTryEnterNeverQueues() {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket first = gate.tryEnter().orElseThrow();
        assertTrue(gate.holds(first));
        assertTrue(gate.tryEnter().isEmpty());
        assertEquals(0, gate.waiting());
        assertTrue(gate.release(first).isEmpty());
        CriticalGate.Ticket next = gate.tryEnter().orElseThrow();
        assertNotSame(first, next);
        assertFalse(gate.holds(first));
        assertTrue(gate.holds(next));
        gate.release(next);
    }

    @Test void queuedCriticalAcquisitionIsFifoAndDoesNotExecuteGuestBody() {
        CriticalGate gate = new CriticalGate();
        var first = gate.request();
        var second = gate.request();
        var third = gate.request();
        assertEquals(CriticalGate.Admission.ACQUIRED, first.admission());
        assertEquals(CriticalGate.Admission.QUEUED, second.admission());
        assertEquals(CriticalGate.Admission.QUEUED, third.admission());
        assertEquals(2, gate.waiting());
        assertFalse(gate.holds(second.ticket()));
        assertFalse(gate.holds(third.ticket()));
        assertTrue(gate.tryEnter().isEmpty());
        assertSame(second.ticket(), gate.release(first.ticket()).orElseThrow());
        assertTrue(gate.holds(second.ticket()));
        assertSame(third.ticket(), gate.release(second.ticket()).orElseThrow());
        assertTrue(gate.holds(third.ticket()));
        assertTrue(gate.release(third.ticket()).isEmpty());
        assertEquals(0, gate.waiting());
    }

    @Test void cancellationBeforeHandoffSkipsWaiter() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.request();
        var cancelled = gate.request();
        var survivor = gate.request();
        assertTrue(gate.release(cancelled.ticket()).isEmpty());
        assertEquals(1, gate.waiting());
        assertSame(survivor.ticket(), gate.release(owner.ticket()).orElseThrow());
        assertFalse(gate.holds(cancelled.ticket()));
        assertTrue(gate.release(survivor.ticket()).isEmpty());
    }

    @Test void duplicateReleaseCannotRevokeTheNextHolder() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.request();
        var next = gate.request();
        assertSame(next.ticket(), gate.release(owner.ticket()).orElseThrow());
        assertTrue(gate.release(owner.ticket()).isEmpty());
        assertTrue(gate.holds(next.ticket()));
        gate.release(next.ticket());
        assertFalse(gate.holds(next.ticket()));
    }

    @Test void foreignScopeDoesNotAcceptTicket() {
        CriticalGate left = new CriticalGate();
        CriticalGate right = new CriticalGate();
        var ticket = left.request().ticket();
        assertFalse(right.holds(ticket));
        assertThrows(IllegalArgumentException.class, () -> right.release(ticket));
        assertTrue(left.holds(ticket));
        left.release(ticket);
    }

    @Test void retiredActorGenerationCannotResumeOrReacquire() {
        CriticalGate old = new CriticalGate();
        var owner = old.request();
        var queued = old.request();
        old.retire();
        assertTrue(old.retired());
        assertEquals(0, old.waiting());
        assertFalse(old.holds(owner.ticket()));
        assertFalse(old.holds(queued.ticket()));
        assertTrue(old.release(owner.ticket()).isEmpty());
        assertTrue(old.release(queued.ticket()).isEmpty());
        assertEquals(Optional.empty(), old.tryEnter());
        assertThrows(IllegalStateException.class, old::request);
        old.retire();

        CriticalGate replacement = new CriticalGate();
        var newTicket = replacement.request().ticket();
        assertFalse(replacement.holds(owner.ticket()));
        assertTrue(replacement.holds(newTicket));
        replacement.release(newTicket);
    }

    @Test void waitingQuotaRejectsOverflowWithoutEvictingOwner() {
        CriticalGate gate = new CriticalGate();
        var owner = gate.request();
        CriticalGate.Ticket firstWaiter = null;
        for (int i = 0; i < CriticalGate.MAX_WAITERS; i++) {
            var request = gate.request();
            if (i == 0) firstWaiter = request.ticket();
            assertEquals(CriticalGate.Admission.QUEUED, request.admission());
        }
        assertEquals(CriticalGate.MAX_WAITERS, gate.waiting());
        assertThrows(IllegalStateException.class, gate::request);
        assertTrue(gate.holds(owner.ticket()));
        gate.release(firstWaiter);
        assertEquals(CriticalGate.MAX_WAITERS - 1, gate.waiting());
        assertEquals(CriticalGate.Admission.QUEUED, gate.request().admission());
        gate.retire();
        assertEquals(0, gate.waiting());
    }

    @Test void cancellingWhileOwnerReleasesAlwaysAdmitsNextLiveTicket()
            throws InterruptedException {
        for (int iteration = 0; iteration < 128; iteration++) {
            CriticalGate gate = new CriticalGate();
            var root = gate.request();
            var cancelled = gate.request();
            var surviving = gate.request();
            Thread releaser = Thread.ofPlatform().start(
                    () -> gate.release(root.ticket()));
            Thread canceller = Thread.ofPlatform().start(
                    () -> gate.release(cancelled.ticket()));
            releaser.join();
            canceller.join();
            assertFalse(gate.holds(cancelled.ticket()), "cancelled ticket held gate");
            assertTrue(gate.holds(surviving.ticket()), "live successor lost during race");
            assertTrue(gate.release(surviving.ticket()).isEmpty());
        }
    }

    @Test void logicalTicketCanBeReleasedOnAnotherCarrier() throws InterruptedException {
        CriticalGate gate = new CriticalGate();
        var owner = gate.request();
        var queued = gate.request();
        AtomicReference<CriticalGate.Ticket> handedOff = new AtomicReference<>();
        Thread otherCarrier = Thread.ofPlatform().start(
                () -> handedOff.set(gate.release(owner.ticket()).orElseThrow()));
        otherCarrier.join();
        assertSame(queued.ticket(), handedOff.get());
        assertTrue(gate.holds(queued.ticket()));
        gate.release(queued.ticket());
    }
}
