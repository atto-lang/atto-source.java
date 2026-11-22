package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Host-only callback unwind and external resource failure diagnostics. */
final class ActorContinuationTerminalResourceFailureTest {
    @Test
    void callbackErrorRemainsPrimaryAndPhysicalDropErrorIsSuppressed() {
        var ledger = new ActorContinuationLedger(1);
        AtomicInteger releaseAttempts = new AtomicInteger();
        AutoCloseable failingDrop = () -> {
            releaseAttempts.incrementAndGet();
            throw new Exception("physical drop failed");
        };
        var receipt = ledger.admit(ActorContinuationLedger.Lane.SOURCE_TASK,
                () -> { throw new IllegalArgumentException("guest callback failure"); },
                failingDrop);
        var failure = assertThrows(IllegalArgumentException.class, receipt::run);
        assertEquals("guest callback failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, failure.getSuppressed()[0]);
        assertEquals("physical drop failed",
                failure.getSuppressed()[0].getCause().getMessage());
        assertEquals(1, releaseAttempts.get());
        assertEquals(0, ledger.snapshot().running());
        assertEquals(0, ledger.snapshot().queued());
        receipt.close();
        assertEquals(1, releaseAttempts.get(), "terminal release must not repeat");
    }

    @Test
    void ordinaryDiscardStillSurfacesDropFailureAndLeavesReceiptTerminal() {
        var ledger = new ActorContinuationLedger(1);
        AtomicInteger releases = new AtomicInteger();
        var receipt = ledger.admit(ActorContinuationLedger.Lane.ORDINARY,
                () -> fail("STOP must discard ordinary guest callback"),
                (AutoCloseable) () -> {
                    releases.incrementAndGet();
                    throw new Exception("cleanup error");
                });
        ledger.sealOrdinary();
        var failure = assertThrows(IllegalStateException.class, receipt::run);
        assertEquals("cleanup error", failure.getCause().getMessage());
        assertEquals(1, releases.get());
        assertEquals(0, ledger.snapshot().queued());
        assertThrows(IllegalStateException.class, receipt::run);
        receipt.close();
        assertEquals(1, releases.get());
    }

    @Test
    void failedAdmissionNeverTransfersOwnershipOfCallerSuppliedResource() throws Exception {
        var ledger = new ActorContinuationLedger(1);
        var original = ledger.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {});
        AtomicInteger releases = new AtomicInteger();
        AutoCloseable wouldBeLease = releases::incrementAndGet;
        assertThrows(IllegalStateException.class, () -> ledger.admit(
                ActorContinuationLedger.Lane.ORDINARY, () -> {}, wouldBeLease));
        assertEquals(0, releases.get(),
                "caller remains responsible for release on failed admission");
        wouldBeLease.close();
        assertEquals(1, releases.get());
        original.close();
    }
}
