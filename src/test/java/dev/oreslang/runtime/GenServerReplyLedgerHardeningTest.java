package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class GenServerReplyLedgerHardeningTest {
    private static GenServerReplyLedger.Target target() {
        return new GenServerReplyLedger.Target(
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                UUID.fromString("00000000-0000-0000-0000-000000000012"), 1);
    }

    @Test
    void capacityAndShutdownUseStableAdmissionReasons() {
        var ledger = new GenServerReplyLedger(1);
        var first = ledger.register(target());
        var full = assertThrows(GenServerReplyLedger.AdmissionException.class,
                () -> ledger.register(target()));
        assertEquals(GenServerReplyLedger.AdmissionException.Reason.CAPACITY, full.reason());
        assertTrue(ledger.retire(first));
        assertEquals(0, ledger.close());
        var closed = assertThrows(GenServerReplyLedger.AdmissionException.class,
                () -> ledger.register(target()));
        assertEquals(GenServerReplyLedger.AdmissionException.Reason.CLOSED, closed.reason());
    }

    @Test
    void staleIncarnationAndDuplicateReplyNeverConsumeAnotherTicket() {
        var ledger = new GenServerReplyLedger(2);
        var current = target();
        var stale = new GenServerReplyLedger.Target(current.runtimeId(), current.actorId(), 2);
        var a = ledger.register(current);
        var b = ledger.register(current);
        assertFalse(ledger.tryReply(a, stale));
        assertEquals(2, ledger.pendingCount());
        assertTrue(ledger.tryReply(a, current));
        assertFalse(ledger.tryReply(a, current));
        assertEquals(1, ledger.pendingCount());
        assertTrue(ledger.tryReply(b, current));
        assertEquals(0, ledger.pendingCount());
    }
}
