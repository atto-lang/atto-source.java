package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class GenServerPendingCallsTest {
    private static GenServerReplyLedger.Target target(long incarnation) {
        return new GenServerReplyLedger.Target(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                incarnation);
    }

    @Test
    void replySettlesFutureExactlyOnceAndFencesIncarnation() throws Exception {
        var calls = new GenServerPendingCalls<String>(4);
        var call = calls.register(target(1));

        assertFalse(calls.tryReply(call, target(2), "stale"));
        assertFalse(call.future().isDone());
        assertTrue(calls.tryReply(call, target(1), "ok"));
        assertEquals("ok", call.future().get(1, TimeUnit.SECONDS));
        assertFalse(calls.tryReply(call, target(1), "duplicate"));
        assertFalse(calls.tryTimeout(call));
        assertFalse(calls.cancel(call));
        assertEquals(0, calls.pendingCount());
    }

    @Test
    void consumerCancellationMustWinLedgerBeforeFutureCancels() {
        var calls = new GenServerPendingCalls<String>(1);
        var call = calls.register(target(1));

        assertTrue(calls.cancel(call));
        assertTrue(call.future().isCancelled());
        assertEquals(0, calls.pendingCount());
        assertFalse(calls.tryReply(call, target(1), "late"));
        assertFalse(calls.tryTimeout(call));
    }

    @Test
    void timeoutWinsCorrelationAndLateReplyCannotReviveFuture() {
        var calls = new GenServerPendingCalls<String>(1);
        var call = calls.register(target(1));

        assertTrue(calls.tryTimeout(call));
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> call.future().get(1, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertFalse(calls.tryReply(call, target(1), "late"));
        assertFalse(calls.cancel(call));
        assertEquals(0, calls.pendingCount());
    }

    @Test
    void closeFailsOnlyCallsThatLoseToShutdown() {
        var calls = new GenServerPendingCalls<String>(4);
        var replied = calls.register(target(1));
        var pendingA = calls.register(target(1));
        var pendingB = calls.register(target(1));

        assertTrue(calls.tryReply(replied, target(1), "done"));
        IllegalStateException shutdown =
                new IllegalStateException("secret runtime shutdown details");
        assertEquals(2, calls.closePending(shutdown));
        assertEquals(0, calls.closePending(shutdown));
        assertEquals("done", assertDoesNotThrow(replied.future()::get));
        for (var call : java.util.List.of(pendingA, pendingB)) {
            ExecutionException failure = assertThrows(
                    ExecutionException.class, call.future()::get);
            var closed = assertInstanceOf(
                    GenServerPendingCalls.GenServerCallClosedException.class,
                    failure.getCause());
            assertEquals(
                    IllegalStateException.class.getName(),
                    closed.diagnosticType());
            assertNull(closed.getCause());
            assertEquals(0, closed.getSuppressed().length);
            assertFalse(closed.getMessage().contains("secret runtime shutdown details"));
            assertNotSame(shutdown, closed);
        }
        assertThrows(IllegalStateException.class, () -> calls.register(target(1)));
    }

    @Test
    void replyAndCancelHaveExactlyOneWinnerAcrossRaces() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 250; iteration++) {
                var calls = new GenServerPendingCalls<Integer>(1);
                var call = calls.register(target(1));
                var ready = new CountDownLatch(1);
                var wins = new AtomicInteger();

                var reply = executor.submit(() -> {
                    ready.await();
                    if (calls.tryReply(call, target(1), 7)) wins.incrementAndGet();
                    return null;
                });
                var cancel = executor.submit(() -> {
                    ready.await();
                    if (calls.cancel(call)) wins.incrementAndGet();
                    return null;
                });

                ready.countDown();
                reply.get(2, TimeUnit.SECONDS);
                cancel.get(2, TimeUnit.SECONDS);

                assertEquals(1, wins.get(), "iteration " + iteration);
                assertEquals(0, calls.pendingCount());
                if (call.future().isCancelled()) {
                    assertThrows(
                            java.util.concurrent.CancellationException.class,
                            call.future()::get);
                } else {
                    assertEquals(7, call.future().get());
                }
            }
        }
    }

    @Test
    void ledgerStatsExposeBoundedPressureWithoutLeakingTickets() {
        var calls = new GenServerPendingCalls<String>(1);
        var first = calls.register(target(1));
        var live = calls.ledgerSnapshot();
        assertEquals(1, live.capacity());
        assertEquals(1, live.pending());
        assertEquals(1, live.registered());
        assertEquals(2, live.nextCorrelation());

        assertThrows(IllegalStateException.class, () -> calls.register(target(1)));
        assertEquals(1, calls.ledgerSnapshot().capacityRejected());

        assertFalse(calls.tryReply(first, target(2), "stale"));
        assertEquals(1, calls.ledgerSnapshot().rejectedClaims());
        assertTrue(calls.tryReply(first, target(1), "ok"));

        var done = calls.ledgerSnapshot();
        assertEquals(0, done.pending());
        assertEquals(1, done.replyClaims());
        assertEquals(1, done.registered());
    }

    @Test
    void foreignRegistryCannotSettleHandle() {
        var left = new GenServerPendingCalls<String>(1);
        var right = new GenServerPendingCalls<String>(1);
        var call = left.register(target(1));

        assertFalse(right.tryReply(call, target(1), "wrong"));
        assertFalse(right.tryTimeout(call));
        assertFalse(right.cancel(call));
        assertFalse(call.future().isDone());
        assertTrue(left.tryReply(call, target(1), "right"));
    }
}
