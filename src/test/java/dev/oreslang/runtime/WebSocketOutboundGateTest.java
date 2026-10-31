package dev.oreslang.runtime;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure host admission/oracle tests; this class does not run Oreslang guests. */
final class WebSocketOutboundGateTest {
    @Test
    void controlRunsBeforeQueuedUserEvenWhenUserCapacityIsFull() throws Exception {
        var aborts = new AtomicInteger();
        var gate = new WebSocketOutboundGate(125, 2, 250, 2, aborts::incrementAndGet);
        var one = gate.enqueueUser(WebSocketFrameCodec.OPCODE_TEXT, new byte[125]);
        var two = gate.enqueueUser(WebSocketFrameCodec.OPCODE_BINARY, new byte[125]);
        assertEquals(250, gate.heldUserBytes());
        assertThrows(RejectedExecutionException.class, () ->
                gate.enqueueUser(WebSocketFrameCodec.OPCODE_TEXT, new byte[1]));
        var pong = gate.enqueueControl(WebSocketFrameCodec.OPCODE_PONG, new byte[] {9});
        assertSame(pong, gate.poll());
        gate.finish(pong, null);
        assertNull(pong.receipt().get());
        assertSame(one, gate.poll());
        gate.finish(one, null);
        assertSame(two, gate.poll());
        gate.finish(two, null);
        assertEquals(0, gate.heldUserBytes());
        assertEquals(0, gate.heldUserCount());
        assertEquals(0, aborts.get());
    }

    @Test
    void byteQuotaIsSeparateFromMessageCountAndReclaimedOnCompletion() {
        var gate = new WebSocketOutboundGate(125, 3, 125, 2, () -> { });
        var first = gate.enqueueUser(WebSocketFrameCodec.OPCODE_BINARY, new byte[100]);
        assertThrows(RejectedExecutionException.class, () ->
                gate.enqueueUser(WebSocketFrameCodec.OPCODE_BINARY, new byte[26]));
        var active = gate.poll();
        assertSame(first, active);
        assertEquals(100, gate.heldUserBytes(), "active frame must retain its byte credit");
        gate.finish(active, null);
        assertEquals(0, gate.heldUserBytes());
        var second = gate.enqueueUser(WebSocketFrameCodec.OPCODE_BINARY, new byte[125]);
        assertEquals(125, gate.heldUserBytes());
        assertTrue(second.receipt().cancel(false));
        assertEquals(0, gate.heldUserBytes());
        assertEquals(0, gate.heldUserCount());
    }

    @Test
    void closeSupersedesWaitingUserAndPongButNotAnActiveWrite() {
        var gate = new WebSocketOutboundGate(125, 3, 375, 2, () -> { });
        var first = gate.enqueueUser(WebSocketFrameCodec.OPCODE_TEXT, new byte[] {'a'});
        assertSame(first, gate.poll());
        var second = gate.enqueueUser(WebSocketFrameCodec.OPCODE_TEXT, new byte[] {'b'});
        var pong = gate.enqueueControl(WebSocketFrameCodec.OPCODE_PONG, new byte[] {1});
        var close = gate.enqueueControl(WebSocketFrameCodec.OPCODE_CLOSE,
                WebSocketSessionState.closePayload(1000, ""));
        assertTrue(second.receipt().isCompletedExceptionally());
        assertTrue(pong.receipt().isCompletedExceptionally());
        assertThrows(RejectedExecutionException.class, () ->
                gate.enqueueUser(WebSocketFrameCodec.OPCODE_TEXT, new byte[1]));
        gate.finish(first, null);
        assertSame(close, gate.poll());
        gate.finish(close, null);
        assertEquals(0, gate.heldUserBytes());
        assertEquals(0, gate.heldControlCount());
    }

    @Test
    void activeCancellationRequestsPhysicalAbortAndRetirementKeepsActiveCredit() {
        var aborts = new AtomicInteger();
        var gate = new WebSocketOutboundGate(125, 2, 250, 2, aborts::incrementAndGet);
        var active = gate.enqueueUser(WebSocketFrameCodec.OPCODE_BINARY, new byte[100]);
        assertSame(active, gate.poll());
        var queued = gate.enqueueUser(WebSocketFrameCodec.OPCODE_TEXT, new byte[50]);
        gate.retire();
        assertTrue(queued.receipt().isCompletedExceptionally());
        assertEquals(100, gate.heldUserBytes(), "physical in-flight loan still owns its bytes");
        assertTrue(active.receipt().cancel(true));
        assertThrows(CancellationException.class, active.receipt()::join);
        assertEquals(1, aborts.get());
        gate.finish(active, new IOException("socket retired"));
        assertEquals(0, gate.heldUserCount());
        assertEquals(0, gate.heldUserBytes());
        gate.retire();
        assertEquals(1, aborts.get());
    }

    @Test
    void invalidControlAndOversizeOutboundFramesFailBeforeQueueAdmission() {
        var gate = new WebSocketOutboundGate(125, 1, 125, 1, () -> { });
        assertThrows(IllegalArgumentException.class, () ->
                gate.enqueueControl(WebSocketFrameCodec.OPCODE_TEXT, new byte[0]));
        assertThrows(IllegalArgumentException.class, () ->
                gate.enqueueControl(WebSocketFrameCodec.OPCODE_PONG, new byte[126]));
        assertThrows(RejectedExecutionException.class, () ->
                gate.enqueueUser(WebSocketFrameCodec.OPCODE_BINARY, new byte[126]));
        assertEquals(0, gate.heldUserCount());
        assertEquals(0, gate.heldControlCount());
    }
}
