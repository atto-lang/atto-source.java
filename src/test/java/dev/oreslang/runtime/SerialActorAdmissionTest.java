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
        assertThrows(IllegalStateException.class, http::requireActive,
                "holding a suspended lifetime must not authorize guest execution");
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
    @Test void thousandsOfInlineHandlerCompletionsAreIterative() {
        // A CompletableFuture dependent action runs inline on complete().
        // The previous implementation recursively called finish() -> complete()
        // for each next handler, eventually overflowing the carrier stack.
        final int total = 4097;
        SerialActorAdmission gate = new SerialActorAdmission(4096);
        var first = gate.submit(HTTP).join();
        AtomicInteger completed = new AtomicInteger();
        List<CompletableFuture<Void>> callbacks = new ArrayList<>();
        for (int i = 0; i < total - 1; i++) {
            var admission = gate.submit(i % 2 == 0 ? MESSAGE : HTTP);
            callbacks.add(admission.thenAccept(lease -> {
                lease.requireActive();
                completed.incrementAndGet();
                lease.close();
            }));
        }
        first.close();
        for (var callback : callbacks) callback.join();
        assertEquals(total - 1, completed.get());
        assertEquals(0, gate.queued());
        assertFalse(gate.busy());
        gate.close();
    }

    @Test void canceledQueuedHandlersAreSkippedWithoutPoisoningFifo() {
        SerialActorAdmission gate = new SerialActorAdmission(16);
        var owner = gate.submit(MESSAGE).join();
        var canceled1 = gate.submit(HTTP);
        var survives1 = gate.submit(MESSAGE);
        var canceled2 = gate.submit(HTTP);
        var survives2 = gate.submit(MESSAGE);
        assertTrue(canceled1.cancel(false));
        assertTrue(canceled2.cancel(false));
        assertEquals(2, gate.queued());
        owner.close();
        var second = survives1.join();
        assertEquals(MESSAGE, second.kind());
        second.close();
        var third = survives2.join();
        assertEquals(MESSAGE, third.kind());
        third.close();
        assertFalse(gate.busy());
        assertEquals(0, gate.queued());
        gate.close();
    }

    @Test void cannotRunSuspendedOrStoppedLease() {
        SerialActorAdmission gate = new SerialActorAdmission(2);
        var lease = gate.submit(HTTP).join();
        lease.requireActive();
        lease.suspend();
        assertThrows(IllegalStateException.class, lease::requireActive);
        lease.resume();
        lease.requireActive();
        gate.close();
        assertThrows(IllegalStateException.class, lease::requireActive);
        assertThrows(IllegalStateException.class, lease::resume);
    }

    @Test void cancellationRacingPromotionNeverLeavesAnOrphanedLease() throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            SerialActorAdmission gate = new SerialActorAdmission(2);
            var owner = gate.submit(HTTP).join();
            var candidate = gate.submit(MESSAGE);
            CountDownLatch start = new CountDownLatch(1);
            Thread cancel = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    candidate.cancel(false);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread finish = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    owner.close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            start.countDown();
            cancel.join();
            finish.join();
            if (!candidate.isCancelled()) {
                var admitted = candidate.join();
                admitted.requireActive();
                admitted.close();
            }
            assertFalse(gate.busy(), "canceled promotion leaked lease in attempt " + attempt);
            assertEquals(0, gate.queued());
            gate.submit(HTTP).join().close(); // Admission must still make progress.
            gate.close();
        }
    }

    @Test void enumLifecycleTracksAdmissionSuspensionAndStopWithoutIndependentFlags() {
        SerialActorAdmission gate = new SerialActorAdmission(2);
        assertEquals(SerialActorAdmission.Lifecycle.IDLE, gate.lifecycle());
        var first = gate.submit(MESSAGE).join();
        assertEquals(SerialActorAdmission.Lifecycle.RUNNING, gate.lifecycle());
        var pending = gate.submit(HTTP);
        first.suspend();
        assertEquals(SerialActorAdmission.Lifecycle.SUSPENDED, gate.lifecycle());
        assertFalse(pending.isDone());
        first.resume();
        assertEquals(SerialActorAdmission.Lifecycle.RUNNING, gate.lifecycle());
        first.close();
        var second = pending.join();
        assertEquals(SerialActorAdmission.Lifecycle.RUNNING, gate.lifecycle());
        second.close();
        assertEquals(SerialActorAdmission.Lifecycle.IDLE, gate.lifecycle());
        gate.close();
        assertEquals(SerialActorAdmission.Lifecycle.STOPPED, gate.lifecycle());
        assertThrows(IllegalStateException.class, () -> gate.submit(MESSAGE));
    }

}
