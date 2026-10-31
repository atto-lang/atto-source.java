package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WebSocketOutboundQueueTest {
    private static WebSocketFrameCodec.Frame user(int size) {
        return new WebSocketFrameCodec.Frame(
                true, WebSocketFrameCodec.OPCODE_BINARY, new byte[size]);
    }

    private static WebSocketFrameCodec.Frame control(int opcode) {
        return new WebSocketFrameCodec.Frame(true, opcode, new byte[] {3});
    }

    private static WebSocketFrameCodec.Frame close() {
        return new WebSocketFrameCodec.Frame(
                true, WebSocketFrameCodec.OPCODE_CLOSE, new byte[] {3, (byte) 232});
    }

    @Test
    void holdsByteAndCountCreditsUntilWriteCompletion() {
        var q = new WebSocketOutboundQueue(1, 4, 2);
        assertEquals(WebSocketOutboundQueue.Admission.ACCEPTED, q.offerUser(user(4)));
        var first = q.acquireWrite().orElseThrow();
        assertEquals(WebSocketOutboundQueue.Admission.CAPACITY, q.offerUser(user(1)));
        assertEquals(1, q.reservedUserFrames());
        assertEquals(4, q.reservedUserBytes());
        assertTrue(q.completeWrite(first, true));
        assertFalse(q.completeWrite(first, true));
        assertEquals(0, q.reservedUserFrames());
        assertEquals(0, q.reservedUserBytes());
        assertEquals(WebSocketOutboundQueue.Admission.ACCEPTED, q.offerUser(user(1)));
    }

    @Test
    void controlOvertakesOnlyQueuedUserAndCannotPreemptActiveLease() {
        var q = new WebSocketOutboundQueue(3, 6, 2);
        q.offerUser(user(2));
        var active = q.acquireWrite().orElseThrow();
        q.offerUser(user(2));
        q.offerControl(control(WebSocketFrameCodec.OPCODE_PONG));
        assertTrue(q.acquireWrite().isEmpty());
        assertTrue(q.completeWrite(active, true));
        var prioritized = q.acquireWrite().orElseThrow();
        assertEquals(WebSocketFrameCodec.OPCODE_PONG, prioritized.frame().opcode());
        assertTrue(q.completeWrite(prioritized, true));
        var queued = q.acquireWrite().orElseThrow();
        assertEquals(WebSocketFrameCodec.OPCODE_BINARY, queued.frame().opcode());
        assertTrue(q.completeWrite(queued, true));
    }

    @Test
    void terminalCloseEvictsQueuedDataAndControlButRetainsActiveCredits() {
        var q = new WebSocketOutboundQueue(3, 8, 1);
        q.offerUser(user(3));
        var active = q.acquireWrite().orElseThrow();
        q.offerUser(user(4));
        q.offerControl(control(WebSocketFrameCodec.OPCODE_PING));
        assertEquals(WebSocketOutboundQueue.Admission.ACCEPTED, q.offerClose(close()));
        assertEquals(1, q.reservedUserFrames());
        assertEquals(3, q.reservedUserBytes());
        assertEquals(1, q.queuedControlFrames());
        assertEquals(WebSocketOutboundQueue.Admission.TERMINAL, q.offerUser(user(1)));
        assertTrue(q.acquireWrite().isEmpty());
        assertTrue(q.completeWrite(active, true));
        var closing = q.acquireWrite().orElseThrow();
        assertEquals(WebSocketFrameCodec.OPCODE_CLOSE, closing.frame().opcode());
        assertTrue(q.completeWrite(closing, true));
        assertTrue(q.closeFlushed());
        assertTrue(q.closing());
        assertFalse(q.closed()); // the socket owner must still perform physical retirement
        q.transportClosed();
        assertTrue(q.closed());
    }

    @Test
    void closeAdmissionSurvivesSaturatedControlCapacity() {
        var q = new WebSocketOutboundQueue(1, 4, 1);
        assertEquals(WebSocketOutboundQueue.Admission.ACCEPTED,
                q.offerControl(control(WebSocketFrameCodec.OPCODE_PONG)));
        assertEquals(WebSocketOutboundQueue.Admission.CAPACITY,
                q.offerControl(control(WebSocketFrameCodec.OPCODE_PING)));
        assertEquals(WebSocketOutboundQueue.Admission.ACCEPTED, q.offerClose(close()));
        assertEquals(WebSocketFrameCodec.OPCODE_CLOSE,
                q.acquireWrite().orElseThrow().frame().opcode());
    }

    @Test
    void retirementRevokesCreditsAndStaleLeasesCannotResurrectQueue() {
        var q = new WebSocketOutboundQueue(1, 4, 2);
        q.offerUser(user(3));
        var old = q.acquireWrite().orElseThrow();
        q.transportClosed();
        q.transportClosed();
        assertFalse(q.completeWrite(old, true));
        assertEquals(0, q.reservedUserBytes());
        assertEquals(0, q.reservedUserFrames());
        assertEquals(WebSocketOutboundQueue.Admission.TERMINAL, q.offerUser(user(1)));
        assertTrue(q.acquireWrite().isEmpty());
    }

    @Test
    void failedWriteRetiresAllPendingFramesOnce() {
        var q = new WebSocketOutboundQueue(2, 6, 1);
        q.offerUser(user(2));
        var first = q.acquireWrite().orElseThrow();
        q.offerUser(user(3));
        assertTrue(q.completeWrite(first, false));
        assertTrue(q.closed());
        assertEquals(0, q.reservedUserBytes());
        assertFalse(q.completeWrite(first, false));
        assertTrue(q.acquireWrite().isEmpty());
    }

    @Test
    void configurationAndOpcodeGatesRejectInvalidQueueInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketOutboundQueue(0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketOutboundQueue(1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketOutboundQueue(1, 1, 0));
        var q = new WebSocketOutboundQueue(1, 1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> q.offerUser(control(WebSocketFrameCodec.OPCODE_PING)));
        assertThrows(IllegalArgumentException.class,
                () -> q.offerControl(user(1)));
        assertThrows(IllegalArgumentException.class,
                () -> q.offerControl(close()));
        assertThrows(IllegalArgumentException.class,
                () -> q.offerClose(user(1)));
    }
}
