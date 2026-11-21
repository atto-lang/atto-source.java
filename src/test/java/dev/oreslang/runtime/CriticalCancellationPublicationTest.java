package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CriticalCancellationPublicationTest {
    @Test
    void cancellingQueuedAdmissionClearsDependencyBeforeNotifyingWaiter() {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket owner = gate.newTicket();
        CriticalGate.Ticket waiting = gate.newTicket();
        assertTrue(owner.enterAsync().isDone());
        OresFuture<Void> completion = waiting.enterAsync();
        assertEquals(1, gate.queuedCount());
        AtomicBoolean clearedOnPublication = new AtomicBoolean();
        completion.whenCompleteRuntime((value, error) ->
                clearedOnPublication.set(gate.queuedCount() == 0
                        && CriticalGate.waitingLogicalDomains() == 0));
        assertTrue(completion.cancel(false));
        assertTrue(clearedOnPublication.get());
        assertEquals(0, gate.queuedCount());
        owner.close();
        assertFalse(gate.held());
    }

    @Test
    void contendedSynchronousSchedulerCallDoesNotRegisterWaiter() {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket owner = gate.newTicket();
        assertTrue(owner.enterAsync().isDone());
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> task = scheduler.start(resume -> {
                CriticalGate.Ticket candidate = gate.newTicket();
                assertThrows(IllegalStateException.class, candidate::enterSync);
                assertEquals(0, gate.queuedCount());
                assertEquals(0, CriticalGate.waitingLogicalDomains());
                return OresScheduler.done(null);
            });
            task.join();
        } finally {
            owner.close();
        }
    }
}
