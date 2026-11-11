package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class HostCapabilityIoLifecycleV1Test {
    @Test void refuseAbsentAuthorityDuplicateDispatchAndPrematureRelease() {
        var gate = new HostCapabilityIoLifecycleV1(1, 2);
        assertNull(gate.reserve(false, true, true, 42, 1, 55));
        assertNull(gate.reserve(true, false, true, 42, 1, 55));
        assertNull(gate.reserve(true, true, false, 42, 1, 55));
        var t = gate.reserve(true, true, true, 42, 1, 55);
        assertNotNull(t);
        assertEquals(1, gate.inFlight());
        assertNull(gate.reserve(true, true, true, 42, 1, 55));
        assertFalse(gate.dispatch(t, 43, 1, 55));
        assertFalse(gate.dispatch(t, 42, 1, 56));
        assertTrue(gate.dispatch(t, 42, 1, 55));
        assertFalse(gate.dispatch(t, 42, 1, 55));
        assertTrue(gate.cancel(t, 42, 1));
        assertFalse(gate.cancel(t, 42, 1));
        assertFalse(gate.retire(t, 42, 1, false));
        assertEquals(1, gate.inFlight());
        assertTrue(gate.retire(t, 42, 1, true));
        assertFalse(gate.retire(t, 42, 1, true));
        assertEquals(0, gate.inFlight());
    }
    @Test void staleTicketAndRevokedIncarnationNeverResumeReplacement() {
        var gate = new HostCapabilityIoLifecycleV1(1, 2);
        var first = gate.reserve(true, true, true, 42, 1, 55);
        assertTrue(gate.complete(first, 42, 1));
        assertTrue(gate.retire(first, 42, 1, true));
        var second = gate.reserve(true, true, true, 42, 1, 55);
        assertNotEquals(first.nonce(), second.nonce());
        assertFalse(gate.dispatch(first, 42, 1, 55));
        assertTrue(gate.revokeActor(42, 1));
        assertFalse(gate.dispatch(second, 42, 1, 55));
        assertNull(gate.reserve(true, true, true, 42, 1, 55));
        assertTrue(gate.complete(second, 42, 1));
        assertTrue(gate.retire(second, 42, 1, true));
        var newActor = gate.reserve(true, true, true, 42, 2, 55);
        assertNotNull(newActor);
        assertTrue(gate.complete(newActor, 42, 2));
        assertTrue(gate.retire(newActor, 42, 2, true));
        assertTrue(gate.revokeActor(42, 2));
        assertTrue(gate.revokeActor(43, 1)); // exhausted revocation storage => fail closed
        assertNull(gate.reserve(true, true, true, 44, 1, 55));
    }
    @Test void shutdownFencesEveryDispatchButDoesNotPretendToCloseSockets() {
        var gate = new HostCapabilityIoLifecycleV1(2, 2);
        var started = gate.reserve(true, true, true, 77, 1, 88);
        var queued = gate.reserve(true, true, true, 78, 1, 89);
        assertNotNull(started);
        assertNotNull(queued);
        assertTrue(gate.dispatch(started, 77, 1, 88));
        assertTrue(gate.shutdown());
        assertFalse(gate.shutdown());
        assertEquals(2, gate.inFlight());
        assertFalse(gate.dispatch(queued, 78, 1, 89));
        assertNull(gate.reserve(true, true, true, 79, 1, 90));
        assertFalse(gate.retire(started, 77, 1, false));
        assertTrue(gate.complete(started, 77, 1));
        assertTrue(gate.retire(started, 77, 1, true));
        assertTrue(gate.retire(queued, 78, 1, true));
        assertEquals(0, gate.inFlight());
    }
    @Test void invalidSignedIdentifiersNeverAdmitOrRevokeAuthority() {
        var gate = new HostCapabilityIoLifecycleV1(1, 2);
        assertNull(gate.reserve(true, true, true, -1, 1, 5));
        assertNull(gate.reserve(true, true, true, 1, -1, 5));
        assertNull(gate.reserve(true, true, true, 1, 1, -5));
        assertFalse(gate.revokeActor(-1, 1));
        assertFalse(gate.revokeActor(1, -1));
        assertEquals(0, gate.inFlight());
    }

    @Test void concurrentStartsCannotReplayOneTicket() throws InterruptedException {
        var gate = new HostCapabilityIoLifecycleV1(1, 1);
        var ticket = gate.reserve(true, true, true, 99, 1, 100);
        assertNotNull(ticket);
        var started = new java.util.concurrent.CountDownLatch(12);
        var release = new java.util.concurrent.CountDownLatch(1);
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        var workers = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 12; i++) {
            var worker = new Thread(() -> {
                started.countDown();
                try {
                    release.await();
                    if (gate.dispatch(ticket, 99, 1, 100)) successes.incrementAndGet();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
            workers.add(worker);
            worker.start();
        }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        release.countDown();
        for (var worker : workers) worker.join(5000);
        assertEquals(1, successes.get());
        assertEquals(1, gate.inFlight());
        assertTrue(gate.complete(ticket, 99, 1));
        assertFalse(gate.retire(ticket, 99, 1, false));
        assertTrue(gate.retire(ticket, 99, 1, true));
    }
}
