package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class GenServerReliabilityTelemetryTest {
    private static GenServerReplyLedger.Target target(long incarnation) {
        return new GenServerReplyLedger.Target(
                UUID.fromString("00000000-0000-0000-0000-000000000031"),
                UUID.fromString("00000000-0000-0000-0000-000000000032"),
                incarnation);
    }

    @Test
    void callLifecycleProducesBoundedTypedEvidenceWithoutPayloads() {
        var time = new AtomicLong(1);
        var kernel = new ActorReliabilityKernel(32, time::getAndIncrement);
        var calls = new GenServerPendingCalls<String>(2, kernel);

        var replied = calls.register(target(1));
        assertFalse(calls.tryReply(replied, target(2), "must-not-appear-in-event"));
        assertTrue(calls.tryReply(replied, target(1), "also-not-telemetry"));

        var timed = calls.register(target(1));
        assertTrue(calls.tryTimeout(timed));

        var cancelled = calls.register(target(1));
        assertTrue(calls.cancel(cancelled));

        var pending = calls.register(target(1));
        var secret = new IllegalStateException(
                "secret shutdown detail must not cross the call boundary");
        calls.closePending(secret);
        var closed = assertThrows(
                java.util.concurrent.ExecutionException.class,
                pending.future()::get);
        var projected = assertInstanceOf(
                GenServerPendingCalls.GenServerCallClosedException.class,
                closed.getCause());
        assertNull(projected.getCause());
        assertEquals(0, projected.getSuppressed().length);
        assertEquals(IllegalStateException.class.getName(), projected.diagnosticType());
        assertFalse(projected.getMessage().contains("secret shutdown detail"));

        var s = kernel.snapshot();
        assertEquals(4L, s.counters().get(ActorReliabilityKernel.EventKind.CALL_REGISTERED));
        assertEquals(1L, s.counters().get(ActorReliabilityKernel.EventKind.CALL_REPLIED));
        assertEquals(1L, s.counters().get(ActorReliabilityKernel.EventKind.CALL_TIMEOUT));
        assertEquals(1L, s.counters().get(ActorReliabilityKernel.EventKind.CALL_CANCELLED));
        assertEquals(1L, s.counters().get(ActorReliabilityKernel.EventKind.CALL_CLOSED));
        assertEquals(1L, s.counters().get(ActorReliabilityKernel.EventKind.CALL_REPLY_REJECTED));
        assertEquals(0, calls.pendingCount());
        assertTrue(pending.future().isCompletedExceptionally());
        for (var e : s.recentEvents()) {
            assertEquals(target(e.actor().incarnation()).runtimeId(), e.actor().runtimeId());
            assertEquals(target(e.actor().incarnation()).actorId(), e.actor().actorId());
        }
    }

    @Test
    void capacityRejectionIsTypedAndCountedAsOverload() {
        var time = new AtomicLong(1);
        var kernel = new ActorReliabilityKernel(8, time::getAndIncrement);
        var calls = new GenServerPendingCalls<String>(1, kernel);
        calls.register(target(1));
        var full = assertThrows(GenServerReplyLedger.AdmissionException.class,
                () -> calls.register(target(1)));
        assertEquals(GenServerReplyLedger.AdmissionException.Reason.CAPACITY, full.reason());
        assertEquals(1L, kernel.snapshot().counters()
                .get(ActorReliabilityKernel.EventKind.OVERLOAD_SHED));
    }

    @Test
    void replyCancelRacesHaveExactlyOneTerminalOutcomePerCall() throws Exception {
        var time = new AtomicLong(1);
        var kernel = new ActorReliabilityKernel(256, time::getAndIncrement);
        int iterations = 500;
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < iterations; i++) {
                var calls = new GenServerPendingCalls<Integer>(1, kernel);
                var call = calls.register(target(1));
                var start = new CountDownLatch(1);
                var wins = new AtomicInteger();
                var reply = executor.submit(() -> {
                    start.await();
                    if (calls.tryReply(call, target(1), 7)) wins.incrementAndGet();
                    return null;
                });
                var cancel = executor.submit(() -> {
                    start.await();
                    if (calls.cancel(call)) wins.incrementAndGet();
                    return null;
                });
                start.countDown();
                reply.get(2, TimeUnit.SECONDS);
                cancel.get(2, TimeUnit.SECONDS);
                assertEquals(1, wins.get(), "race " + i);
                assertEquals(0, calls.pendingCount());
            }
        }
        var counters = kernel.snapshot().counters();
        long terminal = counters.get(ActorReliabilityKernel.EventKind.CALL_REPLIED)
                + counters.get(ActorReliabilityKernel.EventKind.CALL_CANCELLED);
        assertEquals(iterations, terminal);
        assertEquals(iterations,
                counters.get(ActorReliabilityKernel.EventKind.CALL_REGISTERED));
    }
}
