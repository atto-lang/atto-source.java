package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

final class ActorTransportAdmissionTest {
    @Test void threeRequestsFourMessagesPerRequestWithBoundedFifoFairness() {
        try (ActorTransportAdmission admission = new ActorTransportAdmission(3, 4, 2, 2)) {
            var a = admission.requestAsync().join();
            var b = admission.requestAsync().join();
            var c = admission.requestAsync().join();
            assertEquals(3, admission.activeRequestCount());

            var next = admission.requestAsync();
            var last = admission.requestAsync();
            assertFalse(next.isDone());
            assertFalse(last.isDone());
            assertTrue(admission.requestAsync().isCompletedExceptionally());
            assertEquals(2, admission.pendingRequestCount());

            var m1 = a.messageAsync().join();
            var m2 = a.messageAsync().join();
            var m3 = a.messageAsync().join();
            var m4 = a.messageAsync().join();
            assertEquals(4, a.activeMessageCount());

            var blocked = a.messageAsync();
            assertFalse(blocked.isDone());
            var blocked2 = a.messageAsync();
            assertTrue(a.messageAsync().isCompletedExceptionally());

            // Closing a request does not transfer its global request capacity
            // while its four message handlers still own the request lease.
            a.close();
            assertFalse(next.isDone());
            assertTrue(blocked.isCompletedExceptionally());
            assertTrue(blocked2.isCompletedExceptionally());
            m1.close(); m2.close(); m3.close();
            assertFalse(next.isDone());
            m4.close();
            var promoted = next.join();
            assertEquals(3, admission.activeRequestCount());
            promoted.close();
            var finalRequest = last.join();
            finalRequest.close();
            b.close(); c.close();
            assertEquals(0, admission.activeRequestCount());
        }
    }

    @Test void connectionMessageLimitsAreIndependentRatherThanGloballySerialized() {
        try (ActorTransportAdmission admission = new ActorTransportAdmission(3, 4, 0, 0)) {
            var a = admission.requestAsync().join();
            var b = admission.requestAsync().join();
            var c = admission.requestAsync().join();
            var aa = new java.util.ArrayList<ActorTransportAdmission.MessageLease>();
            var bb = new java.util.ArrayList<ActorTransportAdmission.MessageLease>();
            var cc = new java.util.ArrayList<ActorTransportAdmission.MessageLease>();
            for (int i = 0; i < 4; i++) {
                aa.add(a.messageAsync().join());
                bb.add(b.messageAsync().join());
                cc.add(c.messageAsync().join());
            }
            assertEquals(4, a.activeMessageCount());
            assertEquals(4, b.activeMessageCount());
            assertEquals(4, c.activeMessageCount());
            assertTrue(admission.requestAsync().isCompletedExceptionally());
            assertTrue(a.messageAsync().isCompletedExceptionally());
            assertTrue(b.messageAsync().isCompletedExceptionally());
            for (var lease : aa) lease.close();
            for (var lease : bb) lease.close();
            for (var lease : cc) lease.close();
            a.close(); b.close(); c.close();
            assertEquals(0, admission.activeRequestCount());
        }
    }

    @Test void messageCancellationRemovesOnlyItsWaiterAndPreservesFifo() {
        try (ActorTransportAdmission admission = new ActorTransportAdmission(1, 1, 3, 3)) {
            var req = admission.requestAsync().join();
            var first = req.messageAsync().join();
            var skipped = req.messageAsync();
            var next = req.messageAsync();
            assertTrue(skipped.cancel(false));
            assertEquals(1, req.pendingMessageCount());
            first.close();
            var granted = next.join();
            assertEquals(1, req.activeMessageCount());
            granted.close();
            req.close();
        }
    }

    @Test void requestCancellationLeavesNoPhantomSlot() {
        try (ActorTransportAdmission admission = new ActorTransportAdmission(1, 1, 2, 1)) {
            var active = admission.requestAsync().join();
            var cancelled = admission.requestAsync();
            var next = admission.requestAsync();
            assertTrue(cancelled.cancel(false));
            assertEquals(1, admission.pendingRequestCount());
            active.close();
            var admitted = next.join();
            assertEquals(1, admission.activeRequestCount());
            admitted.close();
        }
    }

    @Test void closeFailsPendingWorkAndRejectsLateMessages() {
        var admission = new ActorTransportAdmission(1, 1, 1, 1);
        var request = admission.requestAsync().join();
        var message = request.messageAsync().join();
        var pendingRequest = admission.requestAsync();
        var pendingMessage = request.messageAsync();
        admission.close();
        assertTrue(pendingRequest.isCompletedExceptionally());
        assertTrue(pendingMessage.isCompletedExceptionally());
        assertTrue(request.messageAsync().isCompletedExceptionally());
        assertTrue(admission.requestAsync().isCompletedExceptionally());
        assertEquals(1, admission.activeRequestCount(), "active message still owns request slot");
        message.close();
        assertEquals(0, admission.activeRequestCount());
    }

    @Test void fifoFutureCallbacksDoNotRunUnderAdmissionLock() {
        try (ActorTransportAdmission admission = new ActorTransportAdmission(1, 1, 2, 1)) {
            var first = admission.requestAsync().join();
            var waiting = admission.requestAsync();
            var completed = new java.util.concurrent.CountDownLatch(1);
            waiting.whenCompleteRuntime((granted, failure) -> {
                assertNull(failure);
                var gotMonitor = new java.util.concurrent.CountDownLatch(1);
                Thread.ofPlatform().start(() -> {
                    admission.pendingRequestCount();
                    gotMonitor.countDown();
                });
                try {
                    assertTrue(gotMonitor.await(2, java.util.concurrent.TimeUnit.SECONDS),
                            "grant callback must not execute while monitor is held");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted awaiting admission monitor", interrupted);
                } finally {
                    granted.close();
                    completed.countDown();
                }
            });
            first.close();
            try {
                assertTrue(completed.await(2, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail(interrupted);
            }
            assertEquals(0, admission.activeRequestCount());
        }
    }

    @Test void doubleCloseAndInvalidLimitsAreSafe() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorTransportAdmission(0, 1, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorTransportAdmission(1, 0, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorTransportAdmission(1, 1, -1, 1));
        try (var admission = new ActorTransportAdmission(1, 1, 0, 0)) {
            var req = admission.requestAsync().join();
            var msg = req.messageAsync().join();
            assertTrue(req.messageAsync().isCompletedExceptionally());
            msg.close();
            msg.close();
            req.close();
            req.close();
            assertEquals(0, admission.activeRequestCount());
        }
    }
}
