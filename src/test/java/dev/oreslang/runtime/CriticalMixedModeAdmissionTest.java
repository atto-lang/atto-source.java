package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class CriticalMixedModeAdmissionTest {
    @Test void guestInterpreterUsesRwGateForLegacyCriticalCallables() throws Exception {
        // Merely testing a manually constructed RW gate would miss the old
        // compiler path continuing to allocate a separate CriticalGate.
        Class<?> evaluator = null, object = null;
        for (Class<?> nested : dev.oreslang.nodes.OresEvalRootNode.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("Evaluator")) evaluator = nested;
            if (nested.getSimpleName().equals("OresObject")) object = nested;
        }
        assertNotNull(evaluator, "guest evaluator must exist");
        assertNotNull(object, "guest object implementation must exist");
        assertEquals(CriticalRwGate.class,
                evaluator.getDeclaredField("fileCriticalGate").getType(),
                "legacy file critical must share an RW gate");
        assertTrue(evaluator.getDeclaredField("staticCriticalGates")
                        .getGenericType().getTypeName().contains("CriticalRwGate"),
                "legacy static critical must share an RW gate");
        assertEquals(CriticalRwGate.class,
                object.getDeclaredField("criticalGate").getType(),
                "legacy instance critical must share an RW gate");
    }

    @Test void legacyWriterAndExplicitWriterCannotOverlapReaderCohort() {
        var gate = new CriticalRwGate();
        var readOne = gate.newTicket(CriticalRwGate.Mode.READ);
        var readTwo = gate.newTicket(CriticalRwGate.Mode.READ);
        readOne.enterAsync();
        readTwo.enterAsync();
        var legacy = gate.newCriticalTicket();
        var write = gate.newTicket(CriticalRwGate.Mode.WRITE);
        assertFalse(legacy.enterAsync().isDone());
        assertFalse(write.enterAsync().isDone());
        readOne.close();
        assertFalse(legacy.isHeld());
        readTwo.close();
        assertTrue(legacy.isHeld(), "legacy critical must be an exclusive writer");
        assertFalse(write.isHeld());
        legacy.close();
        assertTrue(write.isHeld(), "explicit writer follows legacy writer in FIFO order");
        write.close();
        assertFalse(gate.hasWriter());
        assertEquals(0, gate.activeReaderCount());
    }

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
