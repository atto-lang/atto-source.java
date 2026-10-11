package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static dev.oreslang.runtime.SerialActorAdmission.Kind.*;

/** Verifies lifetime, cancellation and FIFO across mixed handler kinds. */
final class SerialActorAdmissionTest {
    @Test void suspendedHttpHandlerReservesLaneAgainstMailbox() {
        SerialActorAdmission gate = new SerialActorAdmission(4);
        var http = gate.submit(HTTP).join();
        assertEquals(SerialActorAdmission.State.RUNNING, http.state());
        var message = gate.submit(MESSAGE);
        var secondHttp = gate.submit(HTTP);
        http.suspend();
        assertEquals(SerialActorAdmission.State.SUSPENDED, http.state());
        assertFalse(message.isDone(), "await cannot admit a message");
        assertFalse(secondHttp.isDone(), "await cannot admit another request");
        assertEquals(2, gate.queued());
        http.resume();
        assertFalse(message.isDone());
        http.close();
        var mailLease = message.join();
        assertEquals(MESSAGE, mailLease.kind());
        assertFalse(secondHttp.isDone());
        assertThrows(IllegalStateException.class, http::close);
        assertThrows(IllegalStateException.class, http::resume);
        mailLease.close();
        var next = secondHttp.join();
        assertEquals(HTTP, next.kind());
        next.close();
        assertFalse(gate.busy());
        gate.close();
    }

    @Test void cancellationRemovesQueueReservationAndShutdownFencesOldLeases() {
        SerialActorAdmission gate = new SerialActorAdmission(1);
        var first = gate.submit(MESSAGE).join();
        var canceled = gate.submit(HTTP);
        assertThrows(IllegalStateException.class, () -> gate.submit(MESSAGE));
        assertTrue(canceled.cancel(false));
        assertEquals(0, gate.queued());
        var waiting = gate.submit(MESSAGE);
        gate.close();
        assertTrue(waiting.isCompletedExceptionally());
        assertThrows(IllegalStateException.class, first::suspend);
        assertThrows(IllegalStateException.class, first::close);
        assertThrows(IllegalStateException.class, () -> gate.submit(HTTP));
        gate.close();
    }

    @Test void thousandMixedHandlersNeverOverlapEvenWithCarrierThreads() throws Exception {
        final int total = 1000;
        SerialActorAdmission gate = new SerialActorAdmission(1024);
        AtomicInteger executing = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(total);
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < total; i++) {
                var pending = gate.submit(i % 2 == 0 ? HTTP : MESSAGE);
                completions.add(pending.thenAcceptAsync(lease -> {
                    int active = executing.incrementAndGet();
                    maximum.accumulateAndGet(active, Math::max);
                    try {
                        assertEquals(1, active, "one actor-wide active user handler");
                        completed.incrementAndGet();
                    } finally {
                        executing.decrementAndGet();
                        lease.close();
                        done.countDown();
                    }
                }, executor));
            }
            assertTrue(done.await(30, TimeUnit.SECONDS), "serial mixed handlers must drain");
            for (var future : completions) future.join();
        } finally {
            gate.close();
        }
        assertEquals(total, completed.get());
        assertEquals(1, maximum.get());
        assertFalse(gate.busy());
        assertEquals(0, gate.queued());
    }
}
