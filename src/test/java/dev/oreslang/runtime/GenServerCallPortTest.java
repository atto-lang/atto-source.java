package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GenServerCallPortTest {
    static GenServerReplyLedger.Target actor(long generation) {
        return new GenServerReplyLedger.Target(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), generation);
    }

    @Test void typedFutureRepliesAreFencedBoundedAndCancelable() throws Exception {
        var port = new GenServerCallPort<String>(1);
        var a = port.register(actor(1));
        assertEquals(1, port.pendingCount());
        assertThrows(IllegalStateException.class, () -> port.register(actor(1)));
        assertFalse(port.reply(a.ticket(), actor(2), "stale"));
        assertEquals(1, port.pendingCount());
        assertTrue(port.reply(a.ticket(), actor(1), "done"));
        assertEquals("done", a.future().get());
        assertFalse(port.reply(a.ticket(), actor(1), "duplicate"));
        var b = port.register(actor(1));
        assertTrue(b.future().cancel(false));
        assertFalse(port.reply(b.ticket(), actor(1), "late"));
        assertEquals(0, port.pendingCount());
        assertEquals(0, port.close());
        assertThrows(IllegalStateException.class, () -> port.register(actor(1)));
    }

    @Test void timeoutDoesNotRevokeAlreadyAcceptedServerWork() throws Exception {
        var port = new GenServerCallPort<Integer>(1);
        var a = port.register(actor(1));
        assertTrue(port.timeout(a.ticket()));
        assertEquals(0, port.pendingCount());
        assertThrows(ExecutionException.class, a.future()::get);
        assertFalse(port.reply(a.ticket(), actor(1), 10));
    }

    @Test void crashOnlyFencesAffectedActorGeneration() throws Exception {
        var port = new GenServerCallPort<Integer>(3);
        var a = port.register(actor(1));
        var b = port.register(actor(2));
        var c = port.register(actor(1));
        assertEquals(2, port.failActor(actor(1), new IllegalStateException("failed")));
        assertTrue(a.future().isDone());
        assertTrue(c.future().isDone());
        assertFalse(b.future().isDone());
        assertFalse(port.reply(a.ticket(), actor(1), 1));
        assertTrue(port.reply(b.ticket(), actor(2), 2));
        assertEquals(2, b.future().get());
        assertEquals(0, port.pendingCount());
    }

    @Test void failingOneIncarnationDoesNotTouchSameNumericGenerationInOtherRuntime() throws Exception {
        var port = new GenServerCallPort<Integer>(3);
        var original = actor(1);
        var differentRuntime = new GenServerReplyLedger.Target(
                UUID.fromString("00000000-0000-0000-0000-000000000099"),
                original.actorId(), original.incarnation());
        var same = port.register(original);
        var foreign = port.register(differentRuntime);
        assertEquals(1, port.failActor(original, new IllegalStateException("exited")));
        assertThrows(ExecutionException.class, same.future()::get);
        assertFalse(foreign.future().isDone());
        assertEquals(1, port.pendingCount());
        assertFalse(port.reply(foreign.ticket(), original, 99));
        assertTrue(port.reply(foreign.ticket(), differentRuntime, 7));
        assertEquals(7, foreign.future().get());
        assertEquals(0, port.pendingCount());
    }

    @Test void closeSettlesAllLiveFuturesAndBlocksNewAdmissions() throws Exception {
        var port = new GenServerCallPort<Integer>(3);
        var a = port.register(actor(1));
        var b = port.register(actor(2));
        assertEquals(2, port.close());
        assertEquals(0, port.close());
        assertEquals(0, port.pendingCount());
        assertThrows(ExecutionException.class, a.future()::get);
        assertThrows(ExecutionException.class, b.future()::get);
        assertFalse(port.reply(a.ticket(), actor(1), 4));
    }

    @Test void failedGenerationDoesNotDuplicateOrSettleUnrelatedCallMap() throws Exception {
        var port = new GenServerCallPort<Integer>(258);
        var unaffected = new ArrayList<GenServerCallPort.PendingCall<Integer>>();
        for (int i = 0; i < 256; i++) {
            unaffected.add(port.register(actor(2)));
        }
        var failedA = port.register(actor(1));
        var failedB = port.register(actor(1));
        assertEquals(258, port.pendingCount());
        assertEquals(2, port.failActor(actor(1), new IllegalStateException("actor exited")));
        assertEquals(256, port.pendingCount());
        assertTrue(failedA.future().isDone());
        assertTrue(failedB.future().isDone());
        assertFalse(port.reply(failedA.ticket(), actor(1), 1));
        for (var call : unaffected) {
            assertFalse(call.future().isDone());
            assertTrue(port.reply(call.ticket(), actor(2), 7));
            assertEquals(7, call.future().get());
        }
        assertEquals(0, port.pendingCount());
        assertEquals(0, port.close());
    }

    @Test void closeRetiresOnlyLiveCallsWithoutLosingCancellationWinners() throws Exception {
        var port = new GenServerCallPort<Integer>(64);
        var calls = new ArrayList<GenServerCallPort.PendingCall<Integer>>();
        for (int i = 0; i < 64; i++) calls.add(port.register(actor(1)));
        for (int i = 0; i < calls.size(); i += 2) {
            assertTrue(calls.get(i).future().cancel(false));
        }
        assertEquals(32, port.pendingCount());
        assertEquals(32, port.close());
        assertEquals(0, port.close());
        assertEquals(0, port.pendingCount());
        for (int i = 0; i < calls.size(); i++) {
            var call = calls.get(i);
            if ((i & 1) == 0) {
                assertTrue(call.future().isCancelled());
            } else {
                assertThrows(ExecutionException.class, call.future()::get);
            }
            assertFalse(port.reply(call.ticket(), actor(1), i));
        }
        assertThrows(IllegalStateException.class, () -> port.register(actor(1)));
    }

    @Test void actorFailureAndReplyRacesHaveExactlyOneWinner() throws Exception {
        for (int i = 0; i < 128; i++) {
            var port = new GenServerCallPort<Integer>(1);
            var call = port.register(actor(1));
            CountDownLatch ready = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            try (var executor = Executors.newFixedThreadPool(2)) {
                var sender = executor.submit(() -> {
                    ready.await();
                    if (port.reply(call.ticket(), actor(1), 1)) wins.incrementAndGet();
                    return null;
                });
                var crash = executor.submit(() -> {
                    ready.await();
                    if (port.failActor(actor(1), new IllegalStateException("crash")) == 1) {
                        wins.incrementAndGet();
                    }
                    return null;
                });
                ready.countDown();
                sender.get();
                crash.get();
            }
            assertEquals(1, wins.get());
            assertTrue(call.future().isDone());
            assertEquals(0, port.pendingCount());
            assertFalse(port.reply(call.ticket(), actor(1), 9));
        }
    }

    @Test void perTargetIndexTracksAllTerminalTransitions() throws Exception {
        var port = new GenServerCallPort<Integer>(128);
        var calls = new ArrayList<GenServerCallPort.PendingCall<Integer>>();
        for (int i = 1; i <= 64; i++) calls.add(port.register(actor(i)));
        assertEquals(64, port.pendingCount());
        assertEquals(64, port.pendingTargetCount());

        for (int i = 0; i < 16; i++) {
            assertTrue(port.reply(calls.get(i).ticket(), actor(i + 1), i));
        }
        for (int i = 16; i < 32; i++) {
            assertTrue(calls.get(i).future().cancel(false));
        }
        for (int i = 32; i < 48; i++) {
            assertTrue(port.timeout(calls.get(i).ticket()));
        }
        for (int i = 48; i < 64; i++) {
            assertEquals(1, port.failActor(actor(i + 1),
                    new IllegalStateException("actor failed")));
        }
        assertEquals(0, port.pendingCount());
        assertEquals(0, port.pendingTargetCount());
        assertEquals(0, port.close());
        assertThrows(IllegalStateException.class, () -> port.register(actor(65)));
    }

    @Test void capacityRejectionAndActorRestartDoNotLeakTargetBuckets() throws Exception {
        var port = new GenServerCallPort<Integer>(1);
        var call = port.register(actor(1));
        assertThrows(IllegalStateException.class, () -> port.register(actor(2)));
        assertEquals(1, port.pendingTargetCount());
        assertEquals(1, port.failActor(actor(1), new IllegalStateException("crash")));
        assertEquals(0, port.pendingTargetCount());
        var replacement = port.register(actor(2));
        assertFalse(port.reply(call.ticket(), actor(1), 7));
        assertTrue(port.reply(replacement.ticket(), actor(2), 9));
        assertEquals(9, replacement.future().get());
        assertEquals(0, port.pendingTargetCount());
    }

    @Test void closeCancelAndReplyRaceHaveExactlyOneWinner() throws Exception {
        try (var executor = Executors.newFixedThreadPool(3)) {
            for (int i = 0; i < 128; i++) {
                var port = new GenServerCallPort<Integer>(1);
                var call = port.register(actor(1));
                CountDownLatch ready = new CountDownLatch(1);
                AtomicInteger wins = new AtomicInteger();
                var reply = executor.submit(() -> {
                    ready.await();
                    if (port.reply(call.ticket(), actor(1), 1)) wins.incrementAndGet();
                    return null;
                });
                var cancel = executor.submit(() -> {
                    ready.await();
                    if (call.future().cancel(false)) wins.incrementAndGet();
                    return null;
                });
                var close = executor.submit(() -> {
                    ready.await();
                    if (port.close() == 1) wins.incrementAndGet();
                    return null;
                });
                ready.countDown();
                reply.get();
                cancel.get();
                close.get();
                assertEquals(1, wins.get());
                assertTrue(call.future().isDone());
                assertEquals(0, port.pendingCount());
                assertEquals(0, port.pendingTargetCount());
                assertFalse(port.reply(call.ticket(), actor(1), 2));
            }
        }
    }

    @Test void replyCancellationRacesHaveExactlyOneWinner() throws Exception {
        for (int i = 0; i < 250; i++) {
            var port = new GenServerCallPort<Integer>(1);
            var call = port.register(actor(1));
            CountDownLatch ready = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            try (var executor = Executors.newFixedThreadPool(2)) {
                var sender = executor.submit(() -> {
                    ready.await();
                    if (port.reply(call.ticket(), actor(1), 1)) wins.incrementAndGet();
                    return null;
                });
                var cancel = executor.submit(() -> {
                    ready.await();
                    if (call.future().cancel(false)) wins.incrementAndGet();
                    return null;
                });
                ready.countDown();
                sender.get();
                cancel.get();
            }
            assertEquals(1, wins.get());
            assertTrue(call.future().isDone());
            assertEquals(0, port.pendingCount());
        }
    }
}
