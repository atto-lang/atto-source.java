package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CriticalGateTest {
    @Test void fifoAdmissionTracksLogicalTicketsRatherThanThreads() throws Exception {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket first = gate.newTicket();
        CriticalGate.Ticket second = gate.newTicket();
        CriticalGate.Ticket third = gate.newTicket();
        assertTrue(first.enterAsync().isDone());
        assertFalse(second.enterAsync().isDone());
        assertFalse(third.enterAsync().isDone());
        assertEquals(2, gate.queuedCount());
        Thread releaser = new Thread(first::close, "different-carrier");
        releaser.start(); releaser.join();
        assertFalse(first.isHeld());
        assertTrue(second.isHeld());
        assertFalse(third.isHeld());
        second.close();
        assertTrue(third.isHeld());
        third.close();
        assertFalse(gate.held());
    }

    @Test void cancellationAndFailureReleaseOnlyTheirOwnTicket() {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket first = gate.newTicket();
        CriticalGate.Ticket cancelled = gate.newTicket();
        CriticalGate.Ticket last = gate.newTicket();
        first.enterAsync(); cancelled.enterAsync(); last.enterAsync();
        cancelled.close();
        assertEquals(1, gate.queuedCount());
        first.close();
        assertTrue(last.isHeld());
        last.close();
    }

    @Test void settledTaskCompletionReleasesWithoutReEnteringGuestCode() {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket owner = gate.newTicket();
        owner.enterAsync();
        owner.releaseOnCompletion(OresFuture.completed(42));
        assertFalse(gate.held());
        assertThrows(IllegalStateException.class, owner::enterAsync);
    }

    @Test void gatesAreIndependentAndDoNotShareAStaticProcessLock() {
        CriticalGate a = new CriticalGate(), b = new CriticalGate();
        var first = a.newTicket();
        var second = b.newTicket();
        assertTrue(first.enterAsync().isDone());
        assertTrue(second.enterAsync().isDone());
        first.close(); second.close();
    }

    @Test void contendedSynchronousAttemptOnSchedulerCarrierFailsClosed() throws Exception {
        CriticalGate gate = new CriticalGate();
        var owner = gate.newTicket();
        owner.enterAsync();
        var next = gate.newTicket();
        AtomicBoolean failed = new AtomicBoolean();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> result = scheduler.start(resume -> {
                try { next.enterSync(); }
                catch (IllegalStateException expected) { failed.set(true); }
                return OresScheduler.done(null);
            });
            result.join();
        }
        assertTrue(failed.get());
        assertEquals(0, gate.queuedCount());
        owner.close();
    }
}
