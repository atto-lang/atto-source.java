package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests host routing metadata, NOT complete compiled Atto multi-client I/O. */
final class ActorReplyCorrelationTest {

    private static OresBytes bytes(String text) {
        return OresBytes.copyOf(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ActorReplyCorrelation.Reply reply(
            ActorReplyCorrelation.Pending pending, String status) {
        return new ActorReplyCorrelation.Reply(
                pending.requestId(), pending.sessionId(), pending.generation(),
                status, bytes("opaque"));
    }

    private static void assertRetired(ActorReplyCorrelation.Pending pending) {
        var failure = assertThrows(java.util.concurrent.CompletionException.class,
                pending.response()::join);
        assertInstanceOf(CancellationException.class, failure.getCause());
    }

    @Test
    void exactReplyAndByteOwnership() throws Exception {
        try (var gate = new ActorReplyCorrelation()) {
            var session = gate.openSession(7).orElseThrow();
            var p = gate.tryBegin(session).orElseThrow();
            assertFalse(gate.reply(new ActorReplyCorrelation.Reply(
                    p.requestId(), p.sessionId(), 8, "VALUE", bytes("wrong generation"))));
            assertFalse(gate.reply(new ActorReplyCorrelation.Reply(
                    p.requestId(), p.sessionId() + 1, 7, "VALUE", bytes("wrong client"))));
            assertEquals(1, gate.pendingCount());
            assertTrue(gate.reply(reply(p, "VALUE")));
            assertEquals("VALUE", p.response().get(1, TimeUnit.SECONDS).status());
            assertEquals(bytes("opaque"), p.response().get().payload());
            assertEquals(0, gate.pendingCount());
            assertFalse(gate.reply(reply(p, "VALUE")), "replayed response must be rejected");
        }
    }

    @Test
    void perSessionAndGlobalCreditsAreStrictlyBounded() {
        try (var gate = new ActorReplyCorrelation()) {
            var sessions = new ArrayList<ActorReplyCorrelation.Session>();
            for (int i = 0; i < ActorReplyCorrelation.MAX_SESSIONS; i++)
                sessions.add(gate.openSession(7).orElseThrow());
            assertTrue(gate.openSession(7).isEmpty(), "client count quota");
            for (int i = 0; i < ActorReplyCorrelation.MAX_IN_FLIGHT
                    / ActorReplyCorrelation.MAX_PER_SESSION; i++) {
                var s = sessions.get(i);
                for (int j = 0; j < ActorReplyCorrelation.MAX_PER_SESSION; j++)
                    assertTrue(gate.tryBegin(s).isPresent());
                assertTrue(gate.tryBegin(s).isEmpty(), "per-client quota");
            }
            assertEquals(ActorReplyCorrelation.MAX_IN_FLIGHT, gate.pendingCount());
            assertTrue(gate.tryBegin(sessions.get(20)).isEmpty(), "aggregate quota");
            assertEquals(ActorReplyCorrelation.MAX_PER_SESSION,
                    gate.closeSession(sessions.get(0)));
            assertTrue(gate.tryBegin(sessions.get(20)).isPresent(),
                    "closed session returns global credit");
        }
    }

    @Test
    void cancellationHasExactlyOneTerminalWinner() throws Exception {
        try (var gate = new ActorReplyCorrelation()) {
            var s = gate.openSession(5).orElseThrow();
            var first = gate.tryBegin(s).orElseThrow();
            assertTrue(first.response().cancel(false));
            assertTrue(first.response().isCancelled());
            assertEquals(0, gate.pendingCount());
            assertFalse(gate.reply(reply(first, "OK")));
            var second = gate.tryBegin(s).orElseThrow();
            assertTrue(gate.cancelRequest(s, second.requestId()));
            assertRetired(second);
            assertFalse(gate.cancelRequest(s, second.requestId()));
            assertFalse(gate.reply(reply(second, "OK")));
            assertEquals(0, gate.pendingCount());
        }
    }

    @Test
    void retirementDoesNotCancelUnrelatedSessionsOrGenerations() throws Exception {
        try (var gate = new ActorReplyCorrelation()) {
            var oldA = gate.openSession(1).orElseThrow();
            var oldB = gate.openSession(1).orElseThrow();
            var current = gate.openSession(2).orElseThrow();
            var a = gate.tryBegin(oldA).orElseThrow();
            var b = gate.tryBegin(oldB).orElseThrow();
            var c = gate.tryBegin(current).orElseThrow();
            assertEquals(1, gate.closeSession(oldA));
            assertRetired(a);
            assertFalse(gate.reply(reply(a, "OK")));
            assertEquals(1, gate.retireGeneration(1));
            assertRetired(b);
            assertEquals(1, gate.pendingCount());
            assertTrue(gate.reply(reply(c, "VALUE")));
            assertEquals("VALUE", c.response().get(1, TimeUnit.SECONDS).status());
            assertEquals(0, gate.pendingCount());
            assertTrue(gate.tryBegin(oldB).isEmpty());
            assertTrue(gate.tryBegin(current).isPresent());
        }
    }

    @Test
    void aWrongSessionCannotCancelOrResolveAnotherClient() throws Exception {
        try (var gate = new ActorReplyCorrelation()) {
            var a = gate.openSession(10).orElseThrow();
            var b = gate.openSession(10).orElseThrow();
            var p = gate.tryBegin(a).orElseThrow();
            assertFalse(gate.cancelRequest(b, p.requestId()));
            assertFalse(gate.reply(new ActorReplyCorrelation.Reply(
                    p.requestId(), b.id(), b.generation(), "OK", bytes("wrong"))));
            assertTrue(gate.reply(reply(p, "OK")));
            assertEquals("OK", p.response().get(1, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void closeClearsPendingAndFencesAllLateCompletions() {
        var gate = new ActorReplyCorrelation();
        var s = gate.openSession(1).orElseThrow();
        var pending = gate.tryBegin(s).orElseThrow();
        gate.close();
        gate.close();
        assertEquals(0, gate.pendingCount());
        assertEquals(0, gate.sessionCount());
        assertRetired(pending);
        assertFalse(gate.reply(reply(pending, "OK")));
        assertTrue(gate.tryBegin(s).isEmpty());
        assertTrue(gate.openSession(2).isEmpty());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void concurrentReplyAndCancellationNeverDuplicateDelivery() throws Exception {
        try (var gate = new ActorReplyCorrelation()) {
            var sessions = new ArrayList<ActorReplyCorrelation.Session>();
            for (int i = 0; i < 8; i++)
                sessions.add(gate.openSession(4).orElseThrow());
            AtomicInteger replied = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();
            AtomicInteger rejectedReplay = new AtomicInteger();
            try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
                List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
                for (var session : sessions) {
                    jobs.add(workers.submit(() -> {
                        for (int i = 0; i < 1000; i++) {
                            var p = gate.tryBegin(session).orElseThrow();
                            if ((i & 1) == 0) {
                                assertTrue(gate.reply(reply(p, "VALUE")));
                                assertEquals("VALUE", p.response().join().status());
                                replied.incrementAndGet();
                            } else {
                                assertTrue(p.response().cancel(false));
                                cancelled.incrementAndGet();
                            }
                            if (!gate.reply(reply(p, "VALUE")))
                                rejectedReplay.incrementAndGet();
                        }
                    }));
                }
                for (var job : jobs) job.get(15, TimeUnit.SECONDS);
            }
            assertEquals(4000, replied.get());
            assertEquals(4000, cancelled.get());
            assertEquals(8000, rejectedReplay.get());
            assertEquals(0, gate.pendingCount());
        }
    }

    @Test
    void invalidStatusNeverConsumesCredit() {
        try (var gate = new ActorReplyCorrelation()) {
            var s = gate.openSession(8).orElseThrow();
            var p = gate.tryBegin(s).orElseThrow();
            assertThrows(IllegalArgumentException.class, () ->
                    new ActorReplyCorrelation.Reply(
                            p.requestId(), s.id(), s.generation(),
                            "CUSTOM", OresBytes.empty()));
            assertEquals(1, gate.pendingCount());
            assertTrue(gate.reply(reply(p, "NIL")));
        }
    }
}
