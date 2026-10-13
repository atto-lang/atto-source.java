package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** Both API surfaces MUST use the identical per-domain admission queue. */
final class CriticalGateFacadeParityTest {
    @Test void legacyWriterExcludesRwReadersOnSameInstance() {
        var core = new CriticalGate();
        var facade = core.rwGate();
        assertSame(facade, core.rwGate(), "facade must be cached per protection domain");

        var legacy = core.newTicket();
        var read1 = facade.newTicket(CriticalRwGate.Mode.READ);
        var read2 = facade.newTicket(CriticalRwGate.Mode.READ);
        var write = core.newTicket();

        assertTrue(legacy.enterAsync().isDone());
        assertFalse(read1.enterAsync().isDone());
        assertFalse(read2.enterAsync().isDone());
        assertFalse(write.enterAsync().isDone());
        assertEquals(3, core.queuedCount());
        legacy.close();

        assertTrue(read1.isHeld());
        assertTrue(read2.isHeld());
        assertFalse(write.isHeld());
        read1.close();
        assertFalse(write.isHeld());
        read2.close();
        assertTrue(write.isHeld());
        write.close();
        assertEquals(0, facade.queuedCount());
        assertEquals(0, core.activeReaderCount());
        assertFalse(core.held());
    }

    @Test void cancelFacadeWriterUnblocksOldReadAndRevokesOnStop() {
        var core = new CriticalGate();
        var facade = core.rwGate();
        var active = core.newTicket(CriticalGate.Mode.READ);
        var waitingWrite = facade.newCriticalTicket();
        var followingRead = core.newTicket(CriticalGate.Mode.READ);
        active.enterAsync();
        var wait = waitingWrite.enterAsync();
        followingRead.enterAsync();

        assertFalse(followingRead.isHeld());
        assertTrue(wait.cancel(false));
        assertTrue(followingRead.isHeld());
        assertEquals(2, core.activeReaderCount());
        facade.close();
        assertFalse(active.isHeld());
        assertFalse(followingRead.isHeld());
        assertEquals(0, core.queuedCount());
        assertThrows(IllegalStateException.class, core::newTicket);
    }

    @Test void recursiveSameDomainAccessCannotBypassFacade() {
        var core = new CriticalGate();
        try (var scheduler = new OresScheduler(1)) {
            scheduler.startSync(() -> {
                var legacy = core.newTicket();
                legacy.enterAsync();
                try {
                    var alternate = core.rwGate().newTicket(CriticalRwGate.Mode.READ);
                    var error = assertThrows(IllegalStateException.class,
                            alternate::enterAsync);
                    assertTrue(error.getMessage().contains("recursive"));
                } finally {
                    legacy.close();
                }
                return null;
            }).join();
        }
        assertFalse(core.held());
    }
}
