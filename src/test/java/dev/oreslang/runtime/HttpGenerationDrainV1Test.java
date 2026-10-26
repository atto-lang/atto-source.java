package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only HTTP generation lifetime proof. This intentionally does not claim
 * integration with NativeHttp, ActorRuntime, sockets or Oreslang source actors.
 */
final class HttpGenerationDrainV1Test {
    @Test void currentHttpRequestFinishesOldGenerationAfterPromotion() {
        var old = new HttpGenerationDrainV1(1, 2, 8);
        var request = old.admit();
        old.beginDrain();
        var next = new HttpGenerationDrainV1(2, 2, 8);
        var newRequest = next.admit();

        assertEquals(1, request.generation());
        assertEquals(2, newRequest.generation());
        assertThrows(IllegalStateException.class, old::admit);
        assertFalse(old.readyToRetire());

        request.handlerQuiescent(); // response is still streaming
        assertFalse(old.readyToRetire());
        request.transportQuiescent();
        assertTrue(old.readyToRetire());
        assertFalse(next.readyToRetire());
        newRequest.handlerQuiescent();
        newRequest.transportQuiescent();
        next.beginDrain();
        assertTrue(next.readyToRetire());
    }

    @Test void transportAbortDoesNotFakeHandlerTermination() {
        var ledger = new HttpGenerationDrainV1(31, 1, 8);
        var request = ledger.admit();
        ledger.beginDrain();
        request.requestCancellation();
        request.transportQuiescent();
        assertTrue(request.cancellationRequested());
        assertFalse(request.finished());
        assertFalse(ledger.readyToRetire());
        request.handlerQuiescent();
        assertTrue(ledger.readyToRetire());
    }

    @Test void settledResponseDoesNotReleaseNativeOrSuspendedResource() {
        var ledger = new HttpGenerationDrainV1(42, 1, 8);
        var request = ledger.admit();
        var nativeCall = request.pin(HttpGenerationDrainV1.ResourceKind.FFI_CALL);
        var task = request.pin(HttpGenerationDrainV1.ResourceKind.SUSPENDED_TASK);
        ledger.beginDrain();
        request.handlerQuiescent();
        request.transportQuiescent();
        assertTrue(request.finished());
        assertEquals(2, ledger.resourcePins());
        assertFalse(ledger.readyToRetire());
        assertThrows(IllegalStateException.class,
                () -> request.pin(HttpGenerationDrainV1.ResourceKind.CALLBACK));
        nativeCall.close();
        nativeCall.close();
        assertEquals(1, ledger.resourcePins());
        assertFalse(ledger.readyToRetire());
        task.close();
        assertTrue(ledger.readyToRetire());
    }

    @Test void duplicateCompletionAndPhysicalReleaseAreIdempotent() {
        var ledger = new HttpGenerationDrainV1(7, 1, 8);
        var request = ledger.admit();
        var pin = request.pin(HttpGenerationDrainV1.ResourceKind.HTTP_STREAM);
        request.transportQuiescent();
        request.transportQuiescent();
        request.handlerQuiescent();
        request.handlerQuiescent();
        pin.close();
        pin.close();
        assertEquals(0, ledger.exchanges());
        assertEquals(0, ledger.resourcePins());
        assertFalse(ledger.readyToRetire(), "undrained active generation must not retire");
        ledger.beginDrain();
        ledger.beginDrain();
        assertTrue(ledger.readyToRetire());
    }

    @Test void boundedAdmissionAndFrozenGenerationStayClosed() {
        assertThrows(IllegalArgumentException.class, () -> new HttpGenerationDrainV1(0, 1, 8));
        assertThrows(IllegalArgumentException.class, () -> new HttpGenerationDrainV1(1, 0, 8));
        var ledger = new HttpGenerationDrainV1(15, 1, 8);
        var request = ledger.admit();
        assertThrows(IllegalStateException.class, ledger::admit);
        ledger.beginDrain();
        assertThrows(IllegalStateException.class, ledger::admit);
        request.handlerQuiescent();
        request.transportQuiescent();
        assertTrue(ledger.readyToRetire());
    }

    @Test void physicalResourcePinsHaveIndependentBound() {
        var ledger = new HttpGenerationDrainV1(11, 1, 1);
        var exchange = ledger.admit();
        var pin = exchange.pin(HttpGenerationDrainV1.ResourceKind.HTTP_STREAM);
        assertThrows(IllegalStateException.class,
                () -> exchange.pin(HttpGenerationDrainV1.ResourceKind.CALLBACK));
        exchange.handlerQuiescent();
        exchange.transportQuiescent();
        ledger.beginDrain();
        assertFalse(ledger.readyToRetire());
        pin.close();
        assertTrue(ledger.readyToRetire());
        assertThrows(IllegalArgumentException.class, () -> new HttpGenerationDrainV1(1, 1, 0));
    }

    @Test void oldCodeReferencesSurviveMultipleNewDeploysWithoutForcedPurge() {
        var first = new HttpGenerationDrainV1(1, 1, 2);
        var requestV1 = first.admit();
        var pendingCallback = requestV1.pin(HttpGenerationDrainV1.ResourceKind.CALLBACK);
        first.beginDrain();

        var second = new HttpGenerationDrainV1(2, 1, 2);
        var requestV2 = second.admit();
        second.beginDrain();
        var third = new HttpGenerationDrainV1(3, 1, 2);
        var requestV3 = third.admit();

        requestV1.handlerQuiescent();
        requestV1.transportQuiescent();
        requestV2.handlerQuiescent();
        requestV2.transportQuiescent();
        assertFalse(first.readyToRetire(), "never forcibly purge a pinned V1 image");
        assertTrue(second.readyToRetire(), "V2 may retire independently of V1");
        assertEquals(1, pendingCallback.generation());
        assertEquals(3, requestV3.generation());
        assertEquals(1, first.status().resourcePins());
        assertEquals(1, first.status().resourcesByKind()
                .get(HttpGenerationDrainV1.ResourceKind.CALLBACK).intValue());
        pendingCallback.close();
        assertTrue(first.readyToRetire());
        requestV3.handlerQuiescent();
        requestV3.transportQuiescent();
    }

    @Test void aTimedOutRequestIsNotProofOfPhysicalRelease() {
        var ledger = new HttpGenerationDrainV1(10, 1, 2);
        var request = ledger.admit();
        var nativePin = request.pin(HttpGenerationDrainV1.ResourceKind.FFI_CALL);
        ledger.beginDrain();
        request.requestCancellation();
        request.transportQuiescent();
        assertFalse(ledger.readyToRetire(), "response abort cannot kill in-process FFI");
        request.handlerQuiescent();
        assertFalse(ledger.readyToRetire(), "cancellation is not an FFI return");
        var blockers = ledger.status();
        assertEquals(0, blockers.exchanges());
        assertEquals(1, blockers.resourcePins());
        assertEquals(1, blockers.resourcesByKind().get(
                HttpGenerationDrainV1.ResourceKind.FFI_CALL).intValue());
        nativePin.close();
        assertTrue(ledger.readyToRetire());
    }

    @Test void immutableDiagnosticSnapshotCannotClearOutstandingPins() {
        var ledger = new HttpGenerationDrainV1(12, 1, 3);
        var request = ledger.admit();
        var lease = request.pin(HttpGenerationDrainV1.ResourceKind.SUSPENDED_TASK);
        var original = ledger.status();
        assertThrows(UnsupportedOperationException.class, () ->
                original.resourcesByKind().clear());
        lease.close();
        assertEquals(1, original.resourcePins());
        assertEquals(0, ledger.status().resourcePins());
        request.handlerQuiescent();
        request.transportQuiescent();
        ledger.beginDrain();
        assertTrue(ledger.status().readyToRetire());
    }

    @Test void admissionAndDrainShareALinearizationPoint() throws Exception {
        var ledger = new HttpGenerationDrainV1(19, 1, 8);
        var ready = new CountDownLatch(1);
        var start = new CountDownLatch(1);
        var failed = new AtomicReference<Throwable>();
        var accepted = new AtomicReference<HttpGenerationDrainV1.Exchange>();
        var worker = new Thread(() -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                accepted.set(ledger.admit());
            } catch (IllegalStateException expectedDuringDrain) {
                // Drain won the admission race.
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        worker.setDaemon(true);
        worker.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        ledger.beginDrain();
        worker.join(5000);
        assertFalse(worker.isAlive(), "worker did not terminate");
        assertNull(failed.get());
        assertTrue(ledger.draining());
        assertThrows(IllegalStateException.class, ledger::admit);
        if (accepted.get() != null) {
            assertFalse(ledger.readyToRetire());
            accepted.get().handlerQuiescent();
            accepted.get().transportQuiescent();
        }
        assertTrue(ledger.readyToRetire());
    }
}
