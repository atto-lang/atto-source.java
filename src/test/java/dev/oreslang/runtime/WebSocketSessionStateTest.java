package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WebSocketSessionStateTest {

    @Test
    void queueConfigurationAndLocallyGeneratedCloseCodesFailClosed() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new WebSocketSessionState(
                        WebSocketSessionState.MAX_USER_QUEUE_CAPACITY + 1,
                        1024));

        for (int reserved : new int[] {1004, 1005, 1006, 1014, 1015, 2000, 2999}) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> WebSocketSessionState.closePayload(reserved, "x"),
                    "reserved close code " + reserved);
        }

        assertEquals(
                2,
                WebSocketSessionState.closePayload(1000, "").length);
        assertEquals(
                2,
                WebSocketSessionState.closePayload(3000, "").length);
    }

    @Test
    void closeReasonRequiresStrictUtf8AndActualByteBudget() {
        var valid = WebSocketSessionState.closePayload(1000, "🙂".repeat(30));
        assertEquals(122, valid.length);
        assertArrayEquals("🙂".repeat(30).getBytes(StandardCharsets.UTF_8),
                java.util.Arrays.copyOfRange(valid, 2, valid.length));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> WebSocketSessionState.closePayload(1000, "🙂".repeat(31)),
                "61 UTF-16 units can exceed 123 UTF-8 octets");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> WebSocketSessionState.closePayload(1000, "\uD800"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> WebSocketSessionState.closePayload(1000, "\uDC00"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> WebSocketSessionState.closePayload(1000, "ok\uD800x"));
    }

    @Test
    void controlFramesRemainProcessableWhenUserQueueIsFull() {
        WebSocketSessionState session = new WebSocketSessionState(1, 1024);

        var first = session.accept(new WebSocketFrameCodec.Frame(
                true,
                WebSocketFrameCodec.OPCODE_TEXT,
                "one".getBytes(StandardCharsets.UTF_8)));
        assertEquals(WebSocketSessionState.ActionKind.USER_ENQUEUED, first.kind());
        assertEquals(1, session.queuedUserMessages());

        var ping = session.accept(new WebSocketFrameCodec.Frame(
                true,
                WebSocketFrameCodec.OPCODE_PING,
                new byte[] {1, 2, 3}));
        assertEquals(WebSocketSessionState.ActionKind.SEND_PONG, ping.kind());
        assertArrayEquals(new byte[] {1, 2, 3}, ping.payload());
        assertEquals(1, session.queuedUserMessages());

        var close = session.accept(new WebSocketFrameCodec.Frame(
                true,
                WebSocketFrameCodec.OPCODE_CLOSE,
                WebSocketSessionState.closePayload(1000, "bye")));
        assertEquals(WebSocketSessionState.ActionKind.SEND_CLOSE, close.kind());
        assertEquals(WebSocketSessionState.State.CLOSING, session.state());
        assertEquals(1, session.queuedUserMessages());
    }

    @Test
    void userQueueOverflowFailsClosedWith1013WithoutSilentDropClaim() {
        WebSocketSessionState session = new WebSocketSessionState(1, 1024);

        session.accept(new WebSocketFrameCodec.Frame(
                true,
                WebSocketFrameCodec.OPCODE_TEXT,
                "one".getBytes(StandardCharsets.UTF_8)));

        var overflow = session.accept(new WebSocketFrameCodec.Frame(
                true,
                WebSocketFrameCodec.OPCODE_TEXT,
                "two".getBytes(StandardCharsets.UTF_8)));

        assertEquals(WebSocketSessionState.ActionKind.SEND_CLOSE, overflow.kind());
        byte[] payload = overflow.payload();
        int code = (Byte.toUnsignedInt(payload[0]) << 8)
                | Byte.toUnsignedInt(payload[1]);
        assertEquals(WebSocketSessionState.CLOSE_TRY_AGAIN_LATER, code);
        assertEquals(WebSocketSessionState.State.CLOSING, session.state());

        var queued = session.pollUserMessage();
        assertTrue(queued.isPresent());
        assertEquals(
                "one",
                new String(queued.orElseThrow().payload(), StandardCharsets.UTF_8));
        assertFalse(session.pollUserMessage().isPresent());
    }

    @Test
    void fragmentedMessageConsumesOneUserSlotOnlyAfterCompletion() {
        WebSocketSessionState session = new WebSocketSessionState(1, 32);

        var start = session.accept(new WebSocketFrameCodec.Frame(
                false,
                WebSocketFrameCodec.OPCODE_TEXT,
                "hel".getBytes(StandardCharsets.UTF_8)));
        assertEquals(WebSocketSessionState.ActionKind.NONE, start.kind());
        assertEquals(0, session.queuedUserMessages());

        var ping = session.accept(new WebSocketFrameCodec.Frame(
                true,
                WebSocketFrameCodec.OPCODE_PING,
                new byte[] {9}));
        assertEquals(WebSocketSessionState.ActionKind.SEND_PONG, ping.kind());

        var finish = session.accept(new WebSocketFrameCodec.Frame(
                true,
                WebSocketFrameCodec.OPCODE_CONTINUATION,
                "lo".getBytes(StandardCharsets.UTF_8)));
        assertEquals(WebSocketSessionState.ActionKind.USER_ENQUEUED, finish.kind());
        assertEquals(1, session.queuedUserMessages());
        assertEquals(
                "hello",
                new String(
                        session.pollUserMessage().orElseThrow().payload(),
                        StandardCharsets.UTF_8));
    }

    @Test
    void peerCloseWinsAndDataAfterCloseIsRejectedByState() {
        WebSocketSessionState session = new WebSocketSessionState(2, 32);
        var close = session.accept(new WebSocketFrameCodec.Frame(
                true,
                WebSocketFrameCodec.OPCODE_CLOSE,
                WebSocketSessionState.closePayload(1000, "done")));

        assertEquals(WebSocketSessionState.ActionKind.SEND_CLOSE, close.kind());
        assertEquals(WebSocketSessionState.State.CLOSING, session.state());

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> session.accept(new WebSocketFrameCodec.Frame(
                        true,
                        WebSocketFrameCodec.OPCODE_BINARY,
                        new byte[] {1})));

        session.transportClosed();
        assertEquals(WebSocketSessionState.State.CLOSED, session.state());
        assertEquals(0, session.queuedUserMessages());
    }

    @Test
    void actionPayloadIsDefensivelyCopied() {
        byte[] source = new byte[] {7, 8};
        WebSocketSessionState.Action action =
                new WebSocketSessionState.Action(
                        WebSocketSessionState.ActionKind.SEND_PONG,
                        source);
        source[0] = 99;
        assertArrayEquals(new byte[] {7, 8}, action.payload());
        byte[] leaked = action.payload();
        leaked[1] = 42;
        assertArrayEquals(new byte[] {7, 8}, action.payload());
    }
    @Test
    void userByteQuotaClosesWith1013BeforeCountCapacityAndPingStillWorks() {
        WebSocketSessionState session = new WebSocketSessionState(32, 8, 8);
        assertEquals(WebSocketSessionState.ActionKind.USER_ENQUEUED,
                session.accept(new WebSocketFrameCodec.Frame(
                        true, WebSocketFrameCodec.OPCODE_BINARY,
                        new byte[] {1, 2, 3, 4, 5})).kind());
        assertEquals(5, session.queuedUserBytes());
        assertEquals(1, session.queuedUserMessages());

        var ping = session.accept(new WebSocketFrameCodec.Frame(
                true, WebSocketFrameCodec.OPCODE_PING, new byte[] {9}));
        assertEquals(WebSocketSessionState.ActionKind.SEND_PONG, ping.kind());
        assertArrayEquals(new byte[] {9}, ping.payload());

        var overload = session.accept(new WebSocketFrameCodec.Frame(
                true, WebSocketFrameCodec.OPCODE_BINARY,
                new byte[] {6, 7, 8, 9}));
        assertEquals(WebSocketSessionState.ActionKind.SEND_CLOSE, overload.kind());
        assertEquals(1013, (Byte.toUnsignedInt(overload.payload()[0]) << 8)
                | Byte.toUnsignedInt(overload.payload()[1]));
        assertEquals(WebSocketSessionState.State.CLOSING, session.state());
        assertEquals(1, session.queuedUserMessages());
        assertEquals(5, session.queuedUserBytes());
        assertEquals(5, session.pollUserMessage().orElseThrow().payloadLength());
        assertEquals(0, session.queuedUserBytes());
        assertTrue(session.pollUserMessage().isEmpty());
        session.transportClosed();
        assertEquals(0, session.queuedUserBytes());
    }

    @Test
    void pollingAndPhysicalCloseReleaseExactByteCredits() {
        WebSocketSessionState session = new WebSocketSessionState(3, 3, 5);
        session.accept(new WebSocketFrameCodec.Frame(
                true, WebSocketFrameCodec.OPCODE_BINARY, new byte[] {1, 2, 3}));
        session.accept(new WebSocketFrameCodec.Frame(
                true, WebSocketFrameCodec.OPCODE_BINARY, new byte[] {4, 5}));
        assertEquals(2, session.queuedUserMessages());
        assertEquals(5, session.queuedUserBytes());
        assertEquals(3, session.pollUserMessage().orElseThrow().payloadLength());
        assertEquals(2, session.queuedUserBytes());

        session.accept(new WebSocketFrameCodec.Frame(
                true, WebSocketFrameCodec.OPCODE_BINARY, new byte[] {6, 7, 8}));
        assertEquals(5, session.queuedUserBytes());
        session.transportClosed();
        session.transportClosed(); // repeat retirement must not underflow credits
        assertEquals(0, session.queuedUserBytes());
        assertEquals(0, session.queuedUserMessages());
    }

    @Test
    void queueByteQuotaConfigurationRejectsMemoryAmplification() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WebSocketSessionState(65_536, 64 * 1024 * 1024));
        for (int quota : new int[] {0, -1, WebSocketSessionState.MAX_QUEUED_USER_BYTES + 1}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new WebSocketSessionState(1, 1, quota));
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WebSocketSessionState(1, 9, 8));
    }


    @Test
    void handoffGateDetectsQueuedMessagesAndUnfinishedFragments() {
        WebSocketSessionState session = new WebSocketSessionState(2, 125);
        assertFalse(session.hasPendingUserState());
        assertEquals(WebSocketSessionState.ActionKind.NONE,
                session.accept(new WebSocketFrameCodec.Frame(false,
                        WebSocketFrameCodec.OPCODE_TEXT,
                        new byte[] {'f', 'r'})).kind());
        assertTrue(session.hasPendingUserState(),
                "incomplete fragmented USER frame cannot cross actor transfer");
        assertEquals(WebSocketSessionState.ActionKind.USER_ENQUEUED,
                session.accept(new WebSocketFrameCodec.Frame(true,
                        WebSocketFrameCodec.OPCODE_CONTINUATION,
                        new byte[] {'a', 'g'})).kind());
        assertTrue(session.hasPendingUserState(),
                "completed but undelivered USER frame cannot cross transfer");
        assertArrayEquals(new byte[] {'f', 'r', 'a', 'g'},
                session.pollUserMessage().orElseThrow().payload());
        assertFalse(session.hasPendingUserState());
        session.transportClosed();
        assertFalse(session.hasPendingUserState());
    }

}
