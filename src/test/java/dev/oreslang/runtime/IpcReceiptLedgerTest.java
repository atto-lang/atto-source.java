package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class IpcReceiptLedgerTest {
    private static IpcReceiptLedger.Envelope env(long seq) {
        return new IpcReceiptLedger.Envelope("tenant", "caller", 4, "worker", 7, 3,
                "transport", seq, 1, 2, "a".repeat(64));
    }

    @Test void localRemoteCommittedAreDistinct() {
        var ledger = new IpcReceiptLedger(1);
        var e = env(1);
        ledger.admit(e);
        assertEquals(new IpcReceiptLedger.Budgets(1, 1), ledger.budgets());
        assertThrows(IllegalStateException.class,
                () -> ledger.advance(e, IpcReceiptLedger.Stage.HANDLER_COMMITTED, true));
        ledger.advance(e, IpcReceiptLedger.Stage.REMOTE_ACCEPTED, true);
        assertEquals(new IpcReceiptLedger.Budgets(1, 0), ledger.budgets());
        ledger.advance(e, IpcReceiptLedger.Stage.HANDLER_COMMITTED, true);
        assertEquals(new IpcReceiptLedger.Budgets(0, 0), ledger.budgets());
        assertThrows(IllegalStateException.class,
                () -> ledger.advance(e, IpcReceiptLedger.Stage.HANDLER_COMMITTED, true));
        assertTrue(ledger.invariant());
    }

    @Test void forgedReceiptIdentityAndUnauthenticatedReceiptsRejected() {
        var ledger = new IpcReceiptLedger(1);
        var e = env(1);
        ledger.admit(e);
        assertThrows(IllegalStateException.class,
                () -> ledger.advance(e, IpcReceiptLedger.Stage.REMOTE_ACCEPTED, false));
        var forged = new IpcReceiptLedger.Envelope("tenant", "caller", 4, "worker", 7, 4,
                "transport", 1, 1, 2, "a".repeat(64));
        assertThrows(IllegalStateException.class,
                () -> ledger.advance(forged, IpcReceiptLedger.Stage.REMOTE_ACCEPTED, true));
        assertEquals(new IpcReceiptLedger.Budgets(1, 1), ledger.budgets());
    }

    @Test void noAdmissionFailureIsForbiddenAfterRemoteAcceptance() {
        var ledger = new IpcReceiptLedger(1);
        var e = env(1);
        ledger.admit(e);
        ledger.advance(e, IpcReceiptLedger.Stage.REMOTE_ACCEPTED, true);
        assertThrows(IllegalStateException.class,
                () -> ledger.advance(e, IpcReceiptLedger.Stage.REJECTED_BEFORE_ADMISSION, true));
        ledger.advance(e, IpcReceiptLedger.Stage.HANDLER_FAILED, true);
        assertEquals(new IpcReceiptLedger.Budgets(0, 0), ledger.budgets());
    }

    @Test void transportLossCannotCreateAutomaticReplay() {
        var ledger = new IpcReceiptLedger(1);
        var e = env(1);
        ledger.admit(e);
        assertEquals(1, ledger.markTransportLost());
        assertEquals(0, ledger.markTransportLost());
        assertThrows(IllegalStateException.class,
                () -> ledger.advance(e, IpcReceiptLedger.Stage.HANDLER_COMMITTED, true));
        assertThrows(IllegalStateException.class, () -> ledger.admit(e));
        assertFalse(IpcReceiptLedger.canReplayAutomatically());
        assertTrue(ledger.invariant());
    }

    @Test void correlationCapacityAndMonotonicEpochsAreIndependent() {
        var ledger = new IpcReceiptLedger(1);
        var e = env(1);
        ledger.admit(e);
        var next = new IpcReceiptLedger.Envelope("tenant", "caller", 4, "worker", 7, 4,
                "transport", 2, 1, 3, "a".repeat(64));
        assertThrows(IllegalStateException.class, () -> ledger.admit(next));
        ledger.advance(e, IpcReceiptLedger.Stage.REJECTED_BEFORE_ADMISSION, true);
        ledger.admit(next);
        assertThrows(IllegalStateException.class, () -> ledger.admit(env(3)));
        assertTrue(ledger.invariant());
    }

    @Test void invalidIdentityAndCapacityAreDenied() {
        assertThrows(IllegalArgumentException.class, () -> new IpcReceiptLedger(0));
        assertThrows(IllegalArgumentException.class, () -> new IpcReceiptLedger(65537));
        assertThrows(IllegalArgumentException.class, () -> new IpcReceiptLedger.Envelope(
                "tenant!", "caller", 4, "worker", 7, 3, "transport", 1, 1, 2,
                "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new IpcReceiptLedger.Envelope(
                "tenant", "caller", 4, "worker", 7, 3, "transport", 1, 1, 2,
                "A".repeat(64)));
    }
}
