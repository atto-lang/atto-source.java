package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Host-runtime proof of an authenticated IPC reply-framing boundary. */
@Timeout(20)
final class ReplyTransportGatewayTest {
    private static final ReplySlotRegistry.Endpoint ORIGIN =
            new ReplySlotRegistry.Endpoint("origin", 19, 4);
    private static final ReplySlotRegistry.AuthenticatedPeer CALLEE =
            new ReplySlotRegistry.AuthenticatedPeer(
                    new ReplySlotRegistry.Endpoint("callee", 31, 2), 0x10009);
    private static final String ABI = "sha256:Response-v1";
    private static final int LIMIT = 4096;

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void admittedReplySettlesOnlyItsOwnSlotOnce() throws Exception {
        try (var slots = new ReplySlotRegistry(ORIGIN, 2)) {
            var first = slots.reserve(String.class, ABI, CALLEE);
            var second = slots.reserve(String.class, ABI, CALLEE);
            byte[] ticketA = slots.issueRemoteTicket(first.handle(), CALLEE, ABI);
            byte[] ticketB = slots.issueRemoteTicket(second.handle(), CALLEE, ABI);
            var gateway = new ReplyTransportGateway<>(
                    slots, CALLEE, ABI,
                    (byte[] bytes) -> new String(bytes, StandardCharsets.UTF_8), LIMIT);
            byte[] responseB = ReplyTransportGateway.encodeFrame(
                    second.handle(), ticketB, utf8("second"), LIMIT);
            byte[] responseA = ReplyTransportGateway.encodeFrame(
                    first.handle(), ticketA, utf8("first"), LIMIT);
            assertTrue(gateway.acceptAuthenticatedFrame(responseB));
            assertTrue(gateway.acceptAuthenticatedFrame(responseA));
            assertFalse(gateway.acceptAuthenticatedFrame(responseA), "duplicate drop");
            assertEquals("first", first.future().get(1, TimeUnit.SECONDS));
            assertEquals("second", second.future().get(1, TimeUnit.SECONDS));
            assertEquals(0, slots.pendingCount());
        }
    }

    @Test
    void forgedFrameRejectedBeforeAnyPayloadDecoderIsInvoked() throws Exception {
        try (var slots = new ReplySlotRegistry(ORIGIN, 1)) {
            var pending = slots.reserve(String.class, ABI, CALLEE);
            var ticket = slots.issueRemoteTicket(pending.handle(), CALLEE, ABI);
            AtomicInteger decoded = new AtomicInteger();
            var gateway = new ReplyTransportGateway<>(
                    slots, CALLEE, ABI, (byte[] bytes) -> {
                        decoded.incrementAndGet();
                        return new String(bytes, StandardCharsets.UTF_8);
                    }, LIMIT);
            byte[] frame = ReplyTransportGateway.encodeFrame(
                    pending.handle(), ticket, utf8("safe"), LIMIT);
            byte[] wrongTicket = frame.clone();
            wrongTicket[13] ^= 1; // one byte inside the 16-byte reply ticket
            assertFalse(gateway.acceptAuthenticatedFrame(wrongTicket));
            byte[] wrongHandle = frame.clone();
            wrongHandle[6] ^= 1; // handle is bound to ticket and slot
            assertFalse(gateway.acceptAuthenticatedFrame(wrongHandle));
            byte[] wrongMagic = frame.clone();
            wrongMagic[0] ^= 1;
            assertFalse(gateway.acceptAuthenticatedFrame(wrongMagic));
            byte[] wrongVersion = frame.clone();
            wrongVersion[4] = 2;
            assertFalse(gateway.acceptAuthenticatedFrame(wrongVersion));
            byte[] wrongSize = frame.clone();
            ByteBuffer.wrap(wrongSize).order(ByteOrder.BIG_ENDIAN)
                    .putInt(4 + 1 + 8 + ReplyTicketAuthenticator.TICKET_BYTES, 4095);
            assertFalse(gateway.acceptAuthenticatedFrame(wrongSize));
            byte[] truncated = java.util.Arrays.copyOf(frame, frame.length - 1);
            assertFalse(gateway.acceptAuthenticatedFrame(truncated));
            byte[] trailing = java.util.Arrays.copyOf(frame, frame.length + 1);
            assertFalse(gateway.acceptAuthenticatedFrame(trailing));
            assertFalse(gateway.acceptAuthenticatedFrame(null));
            assertEquals(0, decoded.get());
            assertFalse(pending.future().isDone());
            assertTrue(gateway.acceptAuthenticatedFrame(frame));
            assertEquals(1, decoded.get());
            assertEquals("safe", pending.future().get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void wrongSessionOrSchemaCannotDeliverToPendingCall() throws Exception {
        try (var slots = new ReplySlotRegistry(ORIGIN, 1)) {
            var pending = slots.reserve(String.class, ABI, CALLEE);
            var frame = ReplyTransportGateway.encodeFrame(
                    pending.handle(), slots.issueRemoteTicket(pending.handle(), CALLEE, ABI),
                    utf8("ok"), LIMIT);
            var impostor = new ReplySlotRegistry.AuthenticatedPeer(
                    CALLEE.endpoint(), CALLEE.sessionId() + 1);
            var wrongSession = new ReplyTransportGateway<>(
                    slots, impostor, ABI, (byte[] b) -> "ignored", LIMIT);
            var wrongSchema = new ReplyTransportGateway<>(
                    slots, CALLEE, "sha256:Response-v2", (byte[] b) -> "ignored", LIMIT);
            assertFalse(wrongSession.acceptAuthenticatedFrame(frame));
            assertFalse(wrongSchema.acceptAuthenticatedFrame(frame));
            assertFalse(pending.future().isDone());
            var valid = new ReplyTransportGateway<>(
                    slots, CALLEE, ABI, (byte[] b) -> "ok", LIMIT);
            assertTrue(valid.acceptAuthenticatedFrame(frame));
            assertEquals("ok", pending.future().get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidDecoderDoesNotConsumeFutureAndRacingCancelFencesLateReply() {
        try (var slots = new ReplySlotRegistry(ORIGIN, 1)) {
            var pending = slots.reserve(String.class, ABI, CALLEE);
            var frame = ReplyTransportGateway.encodeFrame(
                    pending.handle(), slots.issueRemoteTicket(pending.handle(), CALLEE, ABI),
                    utf8("value"), LIMIT);
            var throwsOnDecode = new ReplyTransportGateway<String>(
                    slots, CALLEE, ABI, bytes -> {
                        throw new IllegalArgumentException("invalid wire body");
                    }, LIMIT);
            assertFalse(throwsOnDecode.acceptAuthenticatedFrame(frame));
            assertFalse(pending.future().isDone());
            var racingCancellation = new ReplyTransportGateway<String>(
                    slots, CALLEE, ABI, bytes -> {
                        assertTrue(pending.future().cancel(false));
                        return "arrived after cancellation";
                    }, LIMIT);
            assertFalse(racingCancellation.acceptAuthenticatedFrame(frame));
            assertTrue(pending.future().isCancelled());
            assertEquals(0, slots.pendingCount());
        }
    }

    @Test
    void boundedFramesAndNonSerializablePayloadContract() {
        assertThrows(IllegalArgumentException.class,
                () -> ReplyTransportGateway.encodeFrame(1, new byte[15], utf8("x"), LIMIT));
        assertThrows(IllegalArgumentException.class,
                () -> ReplyTransportGateway.encodeFrame(1, new byte[16],
                        new byte[LIMIT], LIMIT));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplyTransportGateway<String>(
                        new ReplySlotRegistry(ORIGIN, 1), CALLEE, ABI,
                        bytes -> "ok", Integer.MAX_VALUE));
    }
}
