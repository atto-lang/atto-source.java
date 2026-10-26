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

        for (int reserved : new int[] {1004, 1005, 1006, 1015, 2000, 2999}) {
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
}
