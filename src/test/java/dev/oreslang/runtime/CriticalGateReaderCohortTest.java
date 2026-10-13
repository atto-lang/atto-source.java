package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class CriticalGateReaderCohortTest {
    @Test void legacyWritersAndReadersUseTheSameQueue() {
        var domain = new CriticalGate();
        var legacyWrite = domain.newTicket();
        var readA = domain.newTicket(CriticalGate.Mode.READ);
        var readB = domain.newTicket(CriticalGate.Mode.READ);
        var explicitWrite = domain.newTicket(CriticalGate.Mode.WRITE);

        assertTrue(legacyWrite.enterAsync().isDone());
        assertFalse(readA.enterAsync().isDone());
        assertFalse(readB.enterAsync().isDone());
        assertFalse(explicitWrite.enterAsync().isDone());
        legacyWrite.close();

        assertTrue(readA.isHeld());
        assertTrue(readB.isHeld());
        assertFalse(explicitWrite.isHeld());
        readA.close();
        assertFalse(explicitWrite.isHeld());
        readB.close();
        assertTrue(explicitWrite.isHeld());
        explicitWrite.close();

        assertEquals(0, domain.activeReaderCount());
        assertEquals(0, domain.queuedCount());
        assertFalse(domain.held());
    }

    @Test void cancellingQueuedWriterOpensReaderCohortImmediately() {
        var domain = new CriticalGate();
        var active = domain.newTicket(CriticalGate.Mode.READ);
        var writer = domain.newTicket();
        var trailing = domain.newTicket(CriticalGate.Mode.READ);
        active.enterAsync();
        var pending = writer.enterAsync();
        trailing.enterAsync();
        assertFalse(trailing.isHeld());
        assertTrue(pending.cancel(false));
        assertTrue(trailing.isHeld());
        assertEquals(2, domain.activeReaderCount());
        active.close();
        trailing.close();
        assertEquals(0, CriticalGate.waitingLogicalDomains());
    }

    @Test void recursiveReaderUpgradeFailsClosed() {
        var domain = new CriticalGate();
        try (var scheduler = new OresScheduler(1)) {
            scheduler.startSync(() -> {
                var reader = domain.newTicket(CriticalGate.Mode.READ);
                reader.enterAsync();
                try {
                    var upgrade = domain.newTicket();
                    var denied = assertThrows(IllegalStateException.class,
                            upgrade::enterAsync);
                    assertTrue(denied.getMessage().contains("recursive logical"));
                } finally {
                    reader.close();
                }
                return null;
            }).join();
        }
        assertFalse(domain.held());
    }
}
