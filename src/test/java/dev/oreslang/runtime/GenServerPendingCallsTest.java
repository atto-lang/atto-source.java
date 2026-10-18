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

    @Test
    void sameIncarnationSelfCallIsRejectedBeforeCorrelationAdmission() {
        var calls = new GenServerPendingCalls<String>(1);
        var self = target(1);

        var rejected = assertThrows(
                GenServerPendingCalls.CallAdmissionException.class,
                () -> calls.registerFrom(self, self));
        assertEquals(
                GenServerPendingCalls.CallAdmissionException.Reason.SELF_CALL,
                rejected.reason());
        assertEquals(0, calls.pendingCount());
        assertEquals(0, calls.ledgerSnapshot().registered());
        assertEquals(1, calls.snapshot().selfCallRejected());

        var nextGeneration = calls.registerFrom(self, target(2));
        assertEquals(1, calls.pendingCount());
        assertTrue(calls.tryReply(nextGeneration, target(2), "ok"));
    }

    @Test
    void opaqueReplyCapabilityIsGenerationFencedAndOneShot() throws Exception {
        var calls = new GenServerPendingCalls<String>(2);
        var call = calls.register(target(7));
        var capability = call.replyCapability();

        assertEquals("GenServerReplyCapability[opaque]", capability.toString());
        assertFalse(capability.tryReply(target(8), "stale"));
        assertFalse(call.future().isDone());
        assertTrue(capability.tryReply(target(7), "ok"));
        assertEquals("ok", call.future().get());
        assertFalse(capability.tryReply(target(7), "duplicate"));
        assertEquals(0, calls.pendingCount());
    }

    @Test
    void monotonicDeadlineQueueHandlesNegativeNanoTimeOriginsAndRejectsRollback()
            throws Exception {
        var calls = new GenServerPendingCalls<String>(4);
        var call = calls.registerTimed(target(1), 10L, -100L);

        assertEquals(1, calls.snapshot().timedPending());
        assertEquals(0, calls.expireDue(-91L));
        assertFalse(call.future().isDone());
        assertEquals(1, calls.expireDue(-90L));
        assertEquals(0, calls.snapshot().timedPending());
        assertEquals(0, calls.snapshot().deadlineBuckets());

        ExecutionException timeout = assertThrows(
                ExecutionException.class, call.future()::get);
        assertInstanceOf(TimeoutException.class, timeout.getCause());

        var regression = assertThrows(
                GenServerPendingCalls.CallAdmissionException.class,
                () -> calls.expireDue(-91L));
        assertEquals(
                GenServerPendingCalls.CallAdmissionException.Reason.CLOCK_REGRESSION,
                regression.reason());
        assertEquals(1, calls.snapshot().clockRegressions());
    }

    @Test
    void deadlineOverflowAndAlreadyExpiredCallsDoNotMintCorrelation() {
        var calls = new GenServerPendingCalls<String>(2);

        var expired = assertThrows(
                GenServerPendingCalls.CallAdmissionException.class,
                () -> calls.registerTimed(target(1), 0L, 5L));
        assertEquals(
                GenServerPendingCalls.CallAdmissionException.Reason.DEADLINE_EXPIRED,
                expired.reason());

        var overflow = assertThrows(
                GenServerPendingCalls.CallAdmissionException.class,
                () -> calls.registerTimed(target(1), 10L, Long.MAX_VALUE - 2L));
        assertEquals(
                GenServerPendingCalls.CallAdmissionException.Reason.DEADLINE_OVERFLOW,
                overflow.reason());

        assertEquals(0, calls.pendingCount());
        assertEquals(0, calls.ledgerSnapshot().registered());
    }

    @Test
    void replyRetiresDeadlineIndexSoLaterTickCannotReviveTimeout() throws Exception {
        var calls = new GenServerPendingCalls<String>(1);
        var call = calls.registerTimed(target(1), 50L, 100L);

        assertTrue(call.replyCapability().tryReply(target(1), "done"));
        assertEquals("done", call.future().get());
        assertEquals(0, calls.snapshot().timedPending());
        assertEquals(0, calls.snapshot().deadlineBuckets());
        assertEquals(0, calls.expireDue(1_000L));
        assertEquals("done", call.future().get());
    }

    @Test
    void targetFailureFencesOnlyExactIncarnationAndDoesNotLeakThrowableGraph()
            throws Exception {
        var calls = new GenServerPendingCalls<String>(8);
        var a = calls.registerTimed(target(1), 1_000L, 100L);
        var b = calls.register(target(1));
        var newer = calls.register(target(2));

        IllegalStateException hostFailure =
                new IllegalStateException("secret child crash details");
        assertEquals(2, calls.failTarget(target(1), hostFailure));
        assertEquals(1, calls.pendingCount());
        assertEquals(2, calls.snapshot().targetFailureSettlements());

        for (var call : java.util.List.of(a, b)) {
            ExecutionException failure = assertThrows(
                    ExecutionException.class, call.future()::get);
            var projected = assertInstanceOf(
                    GenServerPendingCalls.GenServerTargetTerminatedException.class,
                    failure.getCause());
            assertEquals(IllegalStateException.class.getName(), projected.diagnosticType());
            assertNull(projected.getCause());
            assertEquals(0, projected.getSuppressed().length);
            assertFalse(projected.getMessage().contains("secret child crash details"));
            assertNotSame(hostFailure, projected);
        }

        assertFalse(newer.future().isDone());
        assertTrue(calls.tryReply(newer, target(2), "new"));
        assertEquals("new", newer.future().get());
    }

    @Test
    void targetFailureUsesTargetIndexInsteadOfScanningUnrelatedCalls() {
        var calls = new GenServerPendingCalls<Integer>(2_000);
        GenServerReplyLedger.Target doomed = new GenServerReplyLedger.Target(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new UUID(0L, 777L),
                1L);

        for (int i = 1; i <= 1_000; i++) {
            var t = new GenServerReplyLedger.Target(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    new UUID(0L, i),
                    1L);
            calls.register(t);
        }

        assertEquals(1_000, calls.snapshot().targetBuckets());
        assertEquals(1, calls.failTarget(doomed, new IllegalStateException("boom")));
        assertEquals(999, calls.pendingCount());
        assertEquals(999, calls.snapshot().targetBuckets());
        calls.close();
        assertEquals(0, calls.pendingCount());
        assertEquals(0, calls.snapshot().targetBuckets());
    }

    @Test
    void replyTimeoutCancelAndTargetFailureRaceHasExactlyOneWinner() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int iteration = 0; iteration < 300; iteration++) {
                var calls = new GenServerPendingCalls<Integer>(1);
                var call = calls.registerTimed(target(1), 1_000L, 0L);
                var ready = new CountDownLatch(1);
                var wins = new AtomicInteger();

                var reply = executor.submit(() -> {
                    ready.await();
                    if (call.replyCapability().tryReply(target(1), 7)) {
                        wins.incrementAndGet();
                    }
                    return null;
                });
                var timeout = executor.submit(() -> {
                    ready.await();
                    if (calls.tryTimeout(call)) wins.incrementAndGet();
                    return null;
                });
                var cancel = executor.submit(() -> {
                    ready.await();
                    if (calls.cancel(call)) wins.incrementAndGet();
                    return null;
                });
                var crash = executor.submit(() -> {
                    ready.await();
                    if (calls.failTarget(
                            target(1), new IllegalStateException("crash")) == 1) {
                        wins.incrementAndGet();
                    }
                    return null;
                });

                ready.countDown();
                reply.get(2, TimeUnit.SECONDS);
                timeout.get(2, TimeUnit.SECONDS);
                cancel.get(2, TimeUnit.SECONDS);
                crash.get(2, TimeUnit.SECONDS);

                assertEquals(1, wins.get(), "iteration " + iteration);
                assertTrue(call.future().isDone(), "iteration " + iteration);
                assertEquals(0, calls.pendingCount(), "iteration " + iteration);
                assertEquals(0, calls.snapshot().targetBuckets(), "iteration " + iteration);
                assertEquals(0, calls.snapshot().timedPending(), "iteration " + iteration);
                assertEquals(0, calls.snapshot().deadlineBuckets(), "iteration " + iteration);
            }
        }
    }

}
