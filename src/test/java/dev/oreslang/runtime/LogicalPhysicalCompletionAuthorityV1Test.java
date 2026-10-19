package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ledger-level negative proofs for the physical actor receipt adapter.
 * The trusted source actor router invokes completeAfterPhysicalReceipt only
 * after its exact runtime-minted terminal receipt has succeeded.
 */
final class LogicalPhysicalCompletionAuthorityV1Test {
    @Test
    void noPhysicalSubmissionCannotRefundCredits() {
        try (var mailbox = new LogicalActorProxyMailboxV1(2, 8, 16)) {
            var message = mailbox.admit(new byte[] {42});
            assertSame(message, mailbox.pollDispatch());
            var before = mailbox.snapshot();
            assertThrows(SecurityException.class,
                    () -> mailbox.completeAfterPhysicalReceipt(message, 1L));
            assertEquals(before, mailbox.snapshot(),
                    "missing physical attempt cannot mutate the ledger");

            mailbox.beginPhysicalSendAttempt(message, 1L);
            mailbox.completeAfterPhysicalReceipt(message, 1L);
            assertEquals(0, mailbox.snapshot().retainedMessages());
            assertEquals(1L, mailbox.snapshot().completionWatermark());
            assertThrows(SecurityException.class,
                    () -> mailbox.completeAfterPhysicalReceipt(message, 1L),
                    "terminal receipt cannot refund credits twice");
        }
    }

    @Test
    void copiedEnvelopeAndForeignMailboxCannotBorrowReceiptAuthority() {
        try (var mailbox = new LogicalActorProxyMailboxV1(2, 8, 16);
             var foreign = new LogicalActorProxyMailboxV1(2, 8, 16)) {
            var message = mailbox.admit(new byte[] {12});
            var different = foreign.admit(message.payload());
            assertSame(message, mailbox.pollDispatch());
            assertSame(different, foreign.pollDispatch());
            mailbox.beginPhysicalSendAttempt(message, 1L);
            var forged = new LogicalActorProxyMailboxV1.Envelope(
                    message.messageSequence(), message.payload());
            var before = mailbox.snapshot();

            assertThrows(SecurityException.class,
                    () -> mailbox.completeAfterPhysicalReceipt(forged, 1L));
            assertThrows(SecurityException.class,
                    () -> mailbox.completeAfterPhysicalReceipt(message, 2L));
            assertThrows(SecurityException.class,
                    () -> foreign.completeAfterPhysicalReceipt(message, 1L));
            assertEquals(before, mailbox.snapshot());
            assertEquals(1, foreign.snapshot().retainedMessages());

            mailbox.completeAfterPhysicalReceipt(message, 1L);
            assertEquals(0, mailbox.snapshot().retainedMessages());
            // A foreign outstanding envelope has no physical attempt.
            assertThrows(SecurityException.class,
                    () -> foreign.completeAfterPhysicalReceipt(different, 1L));
            foreign.beginPhysicalSendAttempt(different, 1L);
            foreign.completeAfterPhysicalReceipt(different, 1L);
            assertEquals(0, foreign.snapshot().retainedMessages());
        }
    }

    @Test
    void oldRouteReceiptCannotCompleteQueuedNewRouteMessage() {
        try (var mailbox = new LogicalActorProxyMailboxV1(3, 8, 24)) {
            var old = mailbox.admit(new byte[] {1});
            assertSame(old, mailbox.pollDispatch());
            mailbox.beginPhysicalSendAttempt(old, 1L);
            mailbox.completeAfterPhysicalReceipt(old, 1L);

            var ticket = mailbox.freezeDispatchForUpgrade();
            assertTrue(ticket.drained());
            var queued = mailbox.admit(new byte[] {2});
            ticket.commit();
            assertEquals(2L, mailbox.snapshot().routeEpoch());
            assertSame(queued, mailbox.pollDispatch());

            var before = mailbox.snapshot();
            assertThrows(SecurityException.class,
                    () -> mailbox.completeAfterPhysicalReceipt(queued, 1L));
            assertThrows(SecurityException.class,
                    () -> mailbox.completeAfterPhysicalReceipt(old, 1L));
            assertThrows(SecurityException.class,
                    () -> mailbox.completeAfterPhysicalReceipt(queued, 2L),
                    "new route cannot refund credits without physical admission");
            assertEquals(before, mailbox.snapshot());

            mailbox.beginPhysicalSendAttempt(queued, 2L);
            mailbox.completeAfterPhysicalReceipt(queued, 2L);
            assertEquals(2L, mailbox.snapshot().completionWatermark());
            assertEquals(0, mailbox.snapshot().retainedMessages());
        }
    }
}
