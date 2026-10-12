package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

final class PhysicalGuestExitLedgerTest {
    private static HostGeneratorTurnPump.OwnerToken owner() {
        return new HostGeneratorTurnPump.OwnerToken(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void successfulPhysicalLeaveProducesSingleOwnerBoundReceipt() {
        var identity = owner();
        var ledger = new PhysicalGuestExitLedger(identity);
        var entry = ledger.enteredAfterTruffleEnter();
        var receipt = entry.receipt();
        var completed = new AtomicReference<Throwable>();
        receipt.whenCompleteRuntime((ignored, failure) -> completed.set(failure));
        assertTrue(receipt.claim(identity, ledger));
        assertFalse(receipt.claim(identity, ledger), "replay must not claim DONE twice");
        ledger.completedAfterTruffleLeave(entry, null);
        assertNull(completed.get());
        assertTrue(receipt.generation() > 0);
        assertNotNull(receipt.nonce());
        assertThrows(IllegalStateException.class,
                () -> ledger.completedAfterTruffleLeave(entry, null));
    }

    @Test
    void foreignIncarnationCannotClaimReceipt() {
        var identity = owner();
        var ledger = new PhysicalGuestExitLedger(identity);
        var entry = ledger.enteredAfterTruffleEnter();
        assertFalse(entry.receipt().claim(
                new HostGeneratorTurnPump.OwnerToken(identity.contextId(), UUID.randomUUID()), ledger));
        assertFalse(entry.receipt().claim(identity, new PhysicalGuestExitLedger(identity)),
                "same IDs from an unrelated ledger are not sufficient");
        assertTrue(entry.receipt().claim(identity, ledger));
        ledger.completedAfterTruffleLeave(entry, null);
    }

    @Test
    void failedPhysicalLeaveDoesNotProduceSuccessfulDone() {
        var ledger = new PhysicalGuestExitLedger(owner());
        var entry = ledger.enteredAfterTruffleEnter();
        var failed = new AtomicReference<Throwable>();
        entry.receipt().whenCompleteRuntime((ignored, error) -> failed.set(error));
        var reason = new IllegalStateException("Truffle leave failed");
        ledger.completedAfterTruffleLeave(entry, reason);
        assertSame(reason, failed.get());
    }

    @Test
    void nestedEntryAndForeignThreadExitAreFenced() throws Exception {
        var ledger = new PhysicalGuestExitLedger(owner());
        var outer = ledger.enteredAfterTruffleEnter();
        var inner = ledger.enteredAfterTruffleEnter();
        assertThrows(SecurityException.class,
                () -> ledger.completedAfterTruffleLeave(outer, null));
        var denied = new AtomicReference<Throwable>();
        Thread foreign = Thread.ofPlatform().start(() -> {
            try { ledger.completedAfterTruffleLeave(inner, null); }
            catch (Throwable failure) { denied.set(failure); }
        });
        foreign.join();
        assertInstanceOf(SecurityException.class, denied.get());
        ledger.completedAfterTruffleLeave(inner, null);
        ledger.completedAfterTruffleLeave(outer, null);
    }
}
