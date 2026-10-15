package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class NeuralOptimizerStepLedgerTest {
    private static NeuralActorSchema.Ticket ticket(
            UUID request, long epoch, long step, long microbatch) {
        return new NeuralActorSchema.Ticket(request,
                new NeuralActorSchema.ModelVersion("llm", "checkpoint-v3", epoch),
                12, microbatch, step, microbatch * 32);
    }

    @Test
    void requiresCompleteDistinctMicrobatchesBeforeOnceOnlyCommit() {
        UUID request = UUID.randomUUID();
        var root = ticket(request, 5, 22, 0);
        var ledger = new NeuralOptimizerStepLedger(root, 3);
        assertFalse(ledger.readyToCommit());
        ledger.markGradient(ticket(request, 5, 22, 1));
        ledger.markGradient(ticket(request, 5, 22, 0));
        assertThrows(IllegalStateException.class, ledger::commit);
        assertThrows(IllegalStateException.class,
                () -> ledger.markGradient(ticket(request, 5, 22, 1)));
        ledger.markGradient(ticket(request, 5, 22, 2));
        assertTrue(ledger.readyToCommit());
        ledger.commit();
        assertEquals(3, ledger.snapshot().completedMicrobatches());
        assertTrue(ledger.snapshot().committed());
        assertThrows(IllegalStateException.class, ledger::commit);
        assertThrows(IllegalStateException.class,
                () -> ledger.markGradient(ticket(request, 5, 22, 0)));
        assertThrows(IllegalStateException.class, ledger::abort);
    }

    @Test
    void rejectsStaleTopologyIncorrectStepAndOtherModelRequest() {
        UUID request = UUID.randomUUID();
        var ledger = new NeuralOptimizerStepLedger(ticket(request, 5, 22, 0), 2);
        assertThrows(SecurityException.class,
                () -> ledger.markGradient(ticket(request, 6, 22, 0)));
        assertThrows(SecurityException.class,
                () -> ledger.markGradient(ticket(request, 5, 23, 0)));
        assertThrows(SecurityException.class,
                () -> ledger.markGradient(ticket(UUID.randomUUID(), 5, 22, 0)));
        assertThrows(SecurityException.class,
                () -> ledger.markGradient(ticket(request, 5, 22, 2)));
        assertEquals(0, ledger.snapshot().completedMicrobatches());
    }

    @Test
    void abortFencesIncompleteAndAlreadyAbortedSteps() {
        UUID request = UUID.randomUUID();
        var ledger = new NeuralOptimizerStepLedger(ticket(request, 3, 8, 0), 2);
        ledger.markGradient(ticket(request, 3, 8, 0));
        ledger.abort();
        ledger.abort();
        assertFalse(ledger.readyToCommit());
        assertTrue(ledger.snapshot().aborted());
        assertThrows(IllegalStateException.class, ledger::commit);
        assertThrows(IllegalStateException.class,
                () -> ledger.markGradient(ticket(request, 3, 8, 1)));
        assertThrows(IllegalArgumentException.class, () ->
                new NeuralOptimizerStepLedger(ticket(request, 3, -1, 0), 2));
    }
}
