package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

final class LogicalActorProxyEnvelopeIdentityTest {

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void admissionMintsDigestFromMailboxOwnedImmutableBytes() throws Exception {
        try (var mailbox = new LogicalActorProxyMailboxV1(4, 64, 256)) {
            byte[] source = {1, 2, 3, 4, 5};
            var envelope = mailbox.admit(source);

            assertEquals(sha256(source), envelope.payloadSha256());
            assertEquals(64, envelope.payloadSha256().length());

            source[0] = 99;
            byte[] returned = envelope.payload();
            returned[1] = 88;

            assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, envelope.payload());
            assertEquals(
                    sha256(new byte[] {1, 2, 3, 4, 5}),
                    envelope.payloadSha256());
        }
    }

    @Test
    void callerCannotForgeEnvelopeDigest() throws Exception {
        byte[] payload = {9, 8, 7};
        String forged = "0".repeat(64);
        var envelope = new LogicalActorProxyMailboxV1.Envelope(
                1L, payload, forged);

        assertEquals(sha256(payload), envelope.payloadSha256());
        assertNotEquals(forged, envelope.payloadSha256());
    }

    @Test
    void internalPayloadCorruptionFailsBeforePhysicalAttemptReservation()
            throws Exception {
        try (var mailbox = new LogicalActorProxyMailboxV1(2, 64, 128)) {
            var admitted = mailbox.admit(new byte[] {10, 20, 30, 40});
            var envelope = mailbox.pollDispatch();
            assertSame(admitted, envelope);

            Field payload = LogicalActorProxyMailboxV1.Envelope.class
                    .getDeclaredField("payload");
            payload.setAccessible(true);
            byte[] internal = (byte[]) payload.get(envelope);
            internal[0] ^= 0x7f;

            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> mailbox.beginPhysicalSendAttempt(envelope, 1L));
            assertTrue(failure.getMessage().contains("payload identity"));

            var snapshot = mailbox.snapshot();
            assertEquals(1, snapshot.retainedMessages());
            assertEquals(1, snapshot.outstandingMessages());

            // Integrity failure is pre-send, but corrupted bytes remain
            // quarantined in the outstanding ledger and are never restored
            // to the dispatch FIFO.
            assertEquals(0, mailbox.snapshot().queuedMessages());
            assertEquals(1, mailbox.snapshot().outstandingMessages());
        }
    }
}
