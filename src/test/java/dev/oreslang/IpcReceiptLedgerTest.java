package dev.oreslang;

import dev.oreslang.runtime.IpcReceiptLedger;
import dev.oreslang.runtime.IpcReceiptLedger.ReceiptKey;
import dev.oreslang.runtime.IpcReceiptLedger.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

final class IpcReceiptLedgerTest {
    private static ReceiptKey key(long seq) {
        return new ReceiptKey("tenantA", "sender", "worker", "sessionA",
                "a".repeat(64), 4, 7, 8, seq, 1, 2);
    }

    @Test void localReceiptIsNotHandlerCommit() {
        IpcReceiptLedger ledger = new IpcReceiptLedger(2);
        ReceiptKey first = key(1);
        ledger.admit(first);
        assertEquals(new IpcReceiptLedger.Budgets(1, 1), ledger.budgets());
        assertThrows(IllegalStateException.class,
                () -> ledger.advanceVerified(first, Stage.HANDLER_COMMITTED));
        ledger.advanceVerified(first, Stage.REMOTE_ACCEPTED);
        assertEquals(new IpcReceiptLedger.Budgets(1, 0), ledger.budgets());
        assertThrows(IllegalStateException.class,
                () -> ledger.advanceVerified(first, Stage.REJECTED_BEFORE_REMOTE));
        ledger.advanceVerified(first, Stage.HANDLER_COMMITTED);
        assertThrows(IllegalStateException.class,
                () -> ledger.advanceVerified(first, Stage.HANDLER_COMMITTED));
        assertTrue(ledger.invariant());
        assertEquals(0, ledger.budgets().inFlight());
    }

    @Test void identityRouteAndGenerationAreBound() {
        IpcReceiptLedger ledger = new IpcReceiptLedger(1);
        ReceiptKey first = key(1);
        ledger.admit(first);
        ReceiptKey wrongHash = new ReceiptKey("tenantA", "sender", "worker",
                "sessionA", "b".repeat(64), 4, 7, 8, 1, 1, 2);
        ReceiptKey wrongIncarnation = new ReceiptKey("tenantA", "sender", "worker",
                "sessionA", "a".repeat(64), 4, 8, 8, 1, 1, 2);
        for (ReceiptKey wrong : List.of(wrongHash, wrongIncarnation)) {
            assertThrows(IllegalStateException.class,
                    () -> ledger.advanceVerified(wrong, Stage.REMOTE_ACCEPTED));
        }
        assertThrows(IllegalStateException.class, () -> ledger.admit(key(2)));
        ledger.advanceVerified(first, Stage.REJECTED_BEFORE_REMOTE);
        ReceiptKey next = new ReceiptKey("tenantA", "sender", "worker", "sessionB",
                "a".repeat(64), 4, 7, 9, 2, 1, 3);
        ledger.admit(next);
        assertThrows(IllegalStateException.class, () -> ledger.admit(key(3)));
        ledger.advanceVerified(next, Stage.REMOTE_ACCEPTED);
        ledger.advanceVerified(next, Stage.HANDLER_FAILED);
        assertTrue(ledger.invariant());
    }

    @Test void transportLossIsUnknownAndNotAutomaticallyReplayable() {
        IpcReceiptLedger ledger = new IpcReceiptLedger(1);
        ReceiptKey message = key(1);
        ledger.admit(message);
        assertEquals(1, ledger.markTransportLost());
        assertEquals(0, ledger.markTransportLost());
        assertThrows(IllegalStateException.class, () -> ledger.admit(message));
        assertThrows(IllegalStateException.class,
                () -> ledger.advanceVerified(message, Stage.HANDLER_COMMITTED));
        assertFalse(IpcReceiptLedger.mayReplayAutomatically());
        assertTrue(ledger.invariant());
    }

    @Test void rejectMalformedEnvelopeAndCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new IpcReceiptLedger(0));
        assertThrows(IllegalArgumentException.class, () -> new IpcReceiptLedger(65537));
        assertThrows(IllegalArgumentException.class,
                () -> new ReceiptKey("bad!", "s", "d", "session", "a".repeat(64),
                        1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ReceiptKey("tenant", "s", "d", "session", "x",
                        1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ReceiptKey("tenant", "s", "d", "session", "a".repeat(64),
                        1, 1, 1, 0, 1, 1));
    }

    @Test void concurrentAdmissionNeverExceedsBounds() throws Exception {
        IpcReceiptLedger ledger = new IpcReceiptLedger(8);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int actor = 0; actor < 8; actor++) {
                final int index = actor;
                jobs.add(executor.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        ReceiptKey message = key(index * 1000L + i + 1);
                        try {
                            ledger.admit(message);
                            ledger.advanceVerified(message, Stage.REMOTE_ACCEPTED);
                            ledger.advanceVerified(message, Stage.HANDLER_COMMITTED);
                        } catch (IllegalStateException expectedRaceOrBound) {
                            // Never imply a message completed after rejected admission.
                        }
                    }
                }));
            }
            for (Future<?> job : jobs) job.get();
        }
        assertTrue(ledger.invariant());
        assertTrue(ledger.budgets().inFlight() <= 8);
        ledger.markTransportLost();
        assertEquals(new IpcReceiptLedger.Budgets(0, 0), ledger.budgets());
    }
}
