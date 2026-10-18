package dev.oreslang.runtime;

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
