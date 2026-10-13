package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class CriticalMixedModeAdmissionTest {
    @Test void legacyCriticalWriteAndReadersShareOneQueue() {
        var domain = new CriticalRwGate();
        var legacy = domain.newCriticalTicket();
        var readA = domain.newTicket(CriticalRwGate.Mode.READ);
        var readB = domain.newTicket(CriticalRwGate.Mode.READ);
        var writer = domain.newTicket(CriticalRwGate.Mode.WRITE);

        assertEquals(CriticalRwGate.Mode.WRITE, legacy.mode());
        assertTrue(legacy.enterAsync().isDone());
        assertFalse(readA.enterAsync().isDone());
        assertFalse(readB.enterAsync().isDone());
        assertFalse(writer.enterAsync().isDone());
        legacy.close();
        assertTrue(readA.isHeld());
        assertTrue(readB.isHeld());
        assertFalse(writer.isHeld());
        readA.close();
        assertFalse(writer.isHeld());
        readB.close();
        assertTrue(writer.isHeld());
        writer.close();
        assertEquals(0, domain.activeReaderCount());
        assertFalse(domain.hasWriter());
    }

    @Test void synchronousLegacyCriticalCannotParkSchedulerBehindReader() {
        var domain = new CriticalRwGate();
        var reader = domain.newTicket(CriticalRwGate.Mode.READ);
        reader.enterAsync();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            assertTrue(scheduler.startSync(() -> {
                var blockedWrite = domain.newCriticalTicket();
                var failure = assertThrows(IllegalStateException.class, blockedWrite::enterSync);
                assertTrue(failure.getMessage().contains("carrier"));
                return true;
            }).join());
        } finally {
            reader.close();
        }
        assertEquals(0, domain.queuedCount());
    }

    @Test void hostSynchronousReentrancyFailsClosed() {
        var domain = new CriticalRwGate();
        var legacy = domain.newCriticalTicket();
        legacy.enterSync();
        try {
            assertThrows(IllegalStateException.class, domain.newCriticalTicket()::enterSync);
            assertThrows(IllegalStateException.class,
                    domain.newTicket(CriticalRwGate.Mode.READ)::enterSync);
        } finally {
            legacy.close();
        }
        var reader = domain.newTicket(CriticalRwGate.Mode.READ);
        reader.enterSync();
        assertTrue(reader.isHeld());
        reader.close();
    }
}
