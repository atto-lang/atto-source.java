package dev.oreslang.runtime;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real OresFuture + exact generational ledger call-port admission tests.
 * These are host/runtime primitives, NOT compiler/guest actor dispatch.
 */
final class GenServerCallPortTransactionalTest {
    private static GenServerReplyLedger.Target actor(long incarnation) {
        return new GenServerReplyLedger.Target(
                UUID.fromString("00000000-0000-0000-0000-00000000000a"),
                UUID.fromString("00000000-0000-0000-0000-00000000000b"),
                incarnation);
    }

    @Test void synchronousPublicationCanReplyOnceAndReleaseBudget() throws Exception {
        var port = new GenServerCallPort<Integer>(1);
        var admitted = port.registerAndPublish(actor(1),
                ticket -> assertTrue(port.reply(ticket, actor(1), 17)));
        assertEquals(17, admitted.future().get());
        assertEquals(0, port.pendingCount());
        assertEquals(0, port.pendingTargetCount());
        assertFalse(port.reply(admitted.ticket(), actor(1), 99));
    }

    @Test void publicationFailureSettlesFutureWithOriginalCauseAndReleasesCredit() {
        var port = new GenServerCallPort<Integer>(1);
        var denied = new IllegalStateException("destination mailbox saturated");
        var call = port.registerAndPublish(actor(1), ticket -> {
            throw denied;
        });
        ExecutionException failure = assertThrows(
                ExecutionException.class, call.future()::get);
        assertSame(denied, failure.getCause());
        assertEquals(0, port.pendingCount());
        assertEquals(0, port.pendingTargetCount());
        assertFalse(port.reply(call.ticket(), actor(1), 42));
        var next = port.register(actor(1));
        assertTrue(port.reply(next.ticket(), actor(1), 12));
        Integer nextValue = assertDoesNotThrow(
                (org.junit.jupiter.api.function.ThrowingSupplier<Integer>) next.future()::get);
        assertEquals(12, nextValue.intValue());
    }

    @Test void runtimeCancellationRevokesTicketBeforeNotifyingWaiters() {
        var port = new GenServerCallPort<Integer>(1);
        var call = port.register(actor(1));
        var observed = new AtomicBoolean();
        call.future().whenCompleteRuntime((value, failure) -> {
            assertEquals(0, port.pendingCount());
            assertEquals(0, port.pendingTargetCount());
            observed.set(true);
        });
        assertTrue(call.future().cancel(false));
        assertTrue(observed.get());
        assertFalse(port.reply(call.ticket(), actor(1), 1));
        var next = port.register(actor(1));
        assertTrue(port.reply(next.ticket(), actor(1), 2));
        Integer nextValue = assertDoesNotThrow(
                (org.junit.jupiter.api.function.ThrowingSupplier<Integer>) next.future()::get);
        assertEquals(2, nextValue.intValue());
    }

    @Test void authenticTargetAndOriginalTicketAreRequiredAfterReuse() throws Exception {
        var port = new GenServerCallPort<String>(1);
        var old = port.register(actor(1));
        assertTrue(port.timeout(old.ticket()));
        var fresh = port.register(actor(1));
        assertEquals(old.ticket().slotIndex(), fresh.ticket().slotIndex());
        assertNotEquals(old.ticket().slotGeneration(), fresh.ticket().slotGeneration());
        assertFalse(port.reply(old.ticket(), actor(1), "stale"));
        assertFalse(port.reply(fresh.ticket(), actor(2), "restarted"));
        assertTrue(port.reply(fresh.ticket(), actor(1), "fresh"));
        assertEquals("fresh", fresh.future().get());
        assertEquals(0, port.pendingCount());
    }

    @Test void publicationAndCloseRaceCannotStrandTheFuture() throws Exception {
        for (int iteration = 0; iteration < 128; iteration++) {
            var port = new GenServerCallPort<Integer>(1);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<GenServerCallPort.PendingCall<Integer>> result =
                    new AtomicReference<>();
            try (var executor = Executors.newFixedThreadPool(2)) {
                var publisher = executor.submit(() -> {
                    result.set(port.registerAndPublish(actor(1), ticket -> {
                        started.countDown();
                        release.await();
                        throw new IllegalStateException("mailbox closed before enqueue");
                    }));
                    return null;
                });
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, port.close());
                release.countDown();
                publisher.get();
                var call = result.get();
                assertNotNull(call);
                assertTrue(call.future().isDone());
                assertThrows(ExecutionException.class, call.future()::get);
                assertFalse(port.reply(call.ticket(), actor(1), 12));
                assertEquals(0, port.pendingCount());
            }
        }
    }

    @Test void oneHundredConcurrentClientsCanCompleteOutOfOrderWithoutCrossReplies()
            throws Exception {
        var port = new GenServerCallPort<Integer>(100);
        var accepted = new java.util.ArrayList<
                java.util.concurrent.Future<GenServerCallPort.PendingCall<Integer>>>();
        try (var clients = Executors.newFixedThreadPool(12)) {
            for (int i = 0; i < 100; i++) {
                accepted.add(clients.submit(() ->
                        port.registerAndPublish(actor(1), ticket -> { })));
            }
            var calls = new java.util.ArrayList<GenServerCallPort.PendingCall<Integer>>();
            for (var admitted : accepted) {
                calls.add(admitted.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(100, port.pendingCount());
            assertThrows(IllegalStateException.class, () -> port.register(actor(1)));
            for (int i = 99; i >= 0; i--) {
                assertTrue(port.reply(calls.get(i).ticket(), actor(1), i * 2));
            }
            for (int i = 0; i < 100; i++) {
                assertEquals(i * 2, calls.get(i).future().get().intValue());
            }
            assertEquals(0, port.pendingCount());
            assertEquals(0, port.pendingTargetCount());
        }
    }

    @Test void sameTicketReplyVsTimeoutRacesOnlyOneWinner() throws Exception {
        for (int iteration = 0; iteration < 128; iteration++) {
            var port = new GenServerCallPort<Integer>(1);
            var call = port.register(actor(1));
            var start = new CountDownLatch(1);
            var wins = new AtomicInteger();
            try (var executor = Executors.newFixedThreadPool(2)) {
                var reply = executor.submit(() -> {
                    start.await();
                    if (port.reply(call.ticket(), actor(1), 42)) wins.incrementAndGet();
                    return null;
                });
                var timeout = executor.submit(() -> {
                    start.await();
                    if (port.timeout(call.ticket())) wins.incrementAndGet();
                    return null;
                });
                start.countDown();
                reply.get();
                timeout.get();
            }
            assertEquals(1, wins.get());
            assertTrue(call.future().isDone());
            assertEquals(0, port.pendingCount());
            assertEquals(0, port.pendingTargetCount());
        }
    }
}
