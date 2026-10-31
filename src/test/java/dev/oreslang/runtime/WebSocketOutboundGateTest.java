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
    void terminalCloseGetsReservedCreditWhileActivePongSaturatesControlQuota() throws Exception {
        var gate = new WebSocketOutboundGate(125, 2, 250, 1, () -> { });
        var activePong = gate.enqueueControl(
                WebSocketFrameCodec.OPCODE_PONG, new byte[] { 7 });
        assertSame(activePong, gate.poll());
        var waitingUser = gate.enqueueUser(
                WebSocketFrameCodec.OPCODE_TEXT, new byte[] { 'x' });
        assertThrows(RejectedExecutionException.class, () ->
                gate.enqueueControl(WebSocketFrameCodec.OPCODE_PONG, new byte[0]),
                "ordinary CONTROL budget remains full while PONG is active");

        var close = gate.enqueueControl(WebSocketFrameCodec.OPCODE_CLOSE,
                WebSocketSessionState.closePayload(1000, ""));
        assertEquals(2, gate.heldControlCount(),
                "active CONTROL plus one reserved terminal CLOSE is permitted");
        assertEquals(0, gate.heldUserCount(),
                "close revokes queued USER credit before sending");
        assertEquals(0, gate.heldUserBytes());
        assertTrue(waitingUser.receipt().isCompletedExceptionally());
        assertThrows(RejectedExecutionException.class, () ->
                gate.enqueueControl(WebSocketFrameCodec.OPCODE_CLOSE,
                        WebSocketSessionState.closePayload(1000, "")),
                "terminal CLOSE can be enqueued only once");

        gate.finish(activePong, null);
        assertSame(close, gate.poll(), "CLOSE must win before any later USER");
        gate.finish(close, null);
        assertNull(close.receipt().get());
        assertEquals(0, gate.heldControlCount());
        assertEquals(0, gate.heldUserCount());
        assertNull(gate.poll());
        assertFalse(gate.hasPending());
    }

    @Test
    void terminalCloseDropsQueuedControlWhenActiveAndPendingSaturateQuota()
            throws Exception {
        var gate = new WebSocketOutboundGate(125, 2, 250, 2, () -> { });
        var active = gate.enqueueControl(WebSocketFrameCodec.OPCODE_PONG, new byte[] {1});
        assertSame(active, gate.poll());
        var queuedPong = gate.enqueueControl(
                WebSocketFrameCodec.OPCODE_PONG, new byte[] {2});
        var queuedUser = gate.enqueueUser(
                WebSocketFrameCodec.OPCODE_BINARY, new byte[125]);
        var close = gate.enqueueControl(WebSocketFrameCodec.OPCODE_CLOSE,
                WebSocketSessionState.closePayload(1001, ""));
        assertTrue(queuedPong.receipt().isCompletedExceptionally());
        assertTrue(queuedUser.receipt().isCompletedExceptionally());
        assertEquals(2, gate.heldControlCount(),
                "only active PONG and terminal CLOSE may retain credits");
        assertEquals(0, gate.heldUserBytes());
        gate.finish(active, null);
        assertSame(close, gate.poll());
        gate.finish(close, null);
        assertEquals(0, gate.heldControlCount());
        assertEquals(0, gate.heldUserCount());
    }

    @Test
    void cancelledQueuedControlReleasesCreditBeforeTerminalClose() {
        var gate = new WebSocketOutboundGate(125, 1, 125, 1, () -> { });
        var queuedPong = gate.enqueueControl(
                WebSocketFrameCodec.OPCODE_PONG, new byte[] {3});
        assertTrue(queuedPong.receipt().cancel(false));
        assertEquals(0, gate.heldControlCount());
        var close = gate.enqueueControl(WebSocketFrameCodec.OPCODE_CLOSE,
                WebSocketSessionState.closePayload(1000, ""));
        assertSame(close, gate.poll());
        gate.finish(close, null);
        assertEquals(0, gate.heldControlCount());
    }

    @Test
    void cancelledActivePongDoesNotLoseReservedCloseOrPhysicalCredit() {
        var aborts = new AtomicInteger();
        var gate = new WebSocketOutboundGate(
                125, 1, 125, 1, aborts::incrementAndGet);
        var pong = gate.enqueueControl(
                WebSocketFrameCodec.OPCODE_PONG, new byte[] {4});
        assertSame(pong, gate.poll());
        var close = gate.enqueueControl(WebSocketFrameCodec.OPCODE_CLOSE,
                WebSocketSessionState.closePayload(1000, ""));
        assertTrue(pong.receipt().cancel(true));
        assertEquals(1, aborts.get(), "active cancel requests physical socket abort");
        assertEquals(2, gate.heldControlCount(),
                "cancellation cannot claim an unreturned physical credit");
        gate.retire();
        assertTrue(close.receipt().isCompletedExceptionally());
        assertEquals(1, gate.heldControlCount(),
                "active PONG credit is held until worker exits");
        gate.finish(pong, new IOException("aborted"));
        assertEquals(0, gate.heldControlCount());
        assertNull(gate.poll());
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void queuedCancellationRacingTerminalCloseCannotUnderflowOrLoseClose()
            throws Exception {
        for (int round = 0; round < 32; round++) {
            var aborts = new AtomicInteger();
            var gate = new WebSocketOutboundGate(
                    125, 1, 125, 1, aborts::incrementAndGet);
            var activePong = gate.enqueueControl(
                    WebSocketFrameCodec.OPCODE_PONG, new byte[] {1});
            assertSame(activePong, gate.poll());
            var queuedUser = gate.enqueueUser(
                    WebSocketFrameCodec.OPCODE_TEXT, new byte[] {'x'});
            var start = new java.util.concurrent.CountDownLatch(1);
            var raceFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var cancellation = Thread.ofVirtual().start(() -> {
                try {
                    if (!start.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new AssertionError("missing cancellation start");
                    }
                    queuedUser.receipt().cancel(false);
                } catch (Throwable failure) {
                    raceFailure.set(failure);
                }
            });
            start.countDown();
            var close = gate.enqueueControl(
                    WebSocketFrameCodec.OPCODE_CLOSE,
                    WebSocketSessionState.closePayload(1000, ""));
            cancellation.join(3000);
            assertFalse(cancellation.isAlive(), "cancellation blocked in round " + round);
            assertNull(raceFailure.get(), () -> String.valueOf(raceFailure.get()));
            assertEquals(0, gate.heldUserCount(), "round " + round);
            assertEquals(0, gate.heldUserBytes(), "round " + round);
            assertEquals(2, gate.heldControlCount(), "round " + round);
            gate.finish(activePong, null);
            assertSame(close, gate.poll(), "round " + round);
            gate.finish(close, null);
            assertEquals(0, gate.heldControlCount(), "round " + round);
            assertNull(gate.poll(), "round " + round);
            assertEquals(0, aborts.get(), "cancel of queued USER must not abort active PONG");
        }
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
