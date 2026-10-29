package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed host-stage observer tests. These exercise runtime boundaries,
 * not guest compilation, Native Image, or cross-backend acceptance.
 */
final class OresFutureHostAdapterIsolationTest {

    @Test
    void cancellingAnAdaptedObserverDoesNotCancelItsBorrowedHostProducer() {
        CompletableFuture<Integer> producer = new CompletableFuture<>();
        CompletableFuture<Integer> independentObserver = producer.thenApply(value -> value + 1);
        OresFuture<Integer> observed = OresFuture.from(producer);

        assertTrue(observed.cancel(false));
        assertTrue(observed.isCancelled());
        assertFalse(producer.isCancelled(),
                "observer cancellation cannot gain ownership of the host stage");
        assertTrue(producer.complete(41));
        assertEquals(41, producer.join());
        assertEquals(42, independentObserver.join());
        assertThrows(CancellationException.class, observed::join);
    }

    @Test
    void cancellationExceptionFailureWithoutHostCancellationIsNotAuthenticatedCancellation() {
        CompletableFuture<Integer> source = new CompletableFuture<>();
        // CompletionStage propagation can preserve a CancellationException as
        // a failure while producing a distinct stage that is NOT cancelled.
        CompletableFuture<Integer> dependent = source.thenApply(value -> value);
        OresFuture<Integer> observed = OresFuture.from(dependent);

        CancellationException userFailure =
                new CancellationException("user-level exceptional completion");
        assertTrue(source.completeExceptionally(userFailure));
        assertFalse(dependent.isCancelled(),
                "dependent exceptionally completed stage is FAILED, not CANCELLED");

        assertTrue(observed.isDone());
        assertFalse(observed.isCancelled(),
                "exception class alone is not cancellation authority");
        CompletionException failure =
                assertThrows(CompletionException.class, observed::join);
        assertSame(userFailure, failure.getCause(),
                "the original user failure identity must be preserved");
    }

    @Test
    void genuinelyCancelledHostFutureStillAuthenticatesCancellation() {
        CompletableFuture<Integer> ownedHostStage = new CompletableFuture<>();
        OresFuture<Integer> observer = OresFuture.from(ownedHostStage);

        assertTrue(ownedHostStage.cancel(true));
        assertTrue(ownedHostStage.isCancelled());
        assertTrue(observer.isCancelled());
        assertThrows(CancellationException.class, observer::join);
    }

    @Test
    void lateHostCompletionCannotResurrectCancelledObserver() {
        for (int iteration = 0; iteration < 128; iteration++) {
            CompletableFuture<Integer> host = new CompletableFuture<>();
            OresFuture<Integer> observer = OresFuture.from(host);

            assertTrue(observer.cancel(true));
            assertFalse(host.isCancelled(), "cancel must detach, not revoke borrowed host");
            assertTrue(host.complete(iteration));
            assertEquals(iteration, host.join());
            assertTrue(observer.isCancelled());
            assertThrows(CancellationException.class, observer::join);
        }
    }

    @Test
    void notificationDrainReceiptWaitsForBlockedHostRuntimeWaiter() throws Exception {
        OresFuture<Integer> future = new OresFuture<>();
        CountDownLatch waiterEntered = new CountDownLatch(1);
        CountDownLatch releaseWaiter = new CountDownLatch(1);
        AtomicInteger receipts = new AtomicInteger();

        future.whenNotificationsDrainedRuntime(receipts::incrementAndGet);
        future.whenCompleteRuntime((value, failure) -> {
            waiterEntered.countDown();
            try {
                if (!releaseWaiter.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release host completion waiter");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });

        Thread settler = Thread.ofVirtual().start(() -> future.completeFromRuntime(42));
        try {
            assertTrue(waiterEntered.await(5, TimeUnit.SECONDS),
                    "the host waiter must be physically entered before inspection");
            assertTrue(future.isDone(), "logical completion is visible at terminal CAS");
            assertEquals(0, receipts.get(),
                    "logical Future settlement is not a notification-drain receipt");
        } finally {
            releaseWaiter.countDown();
            settler.join();
        }

        assertEquals(42, future.join());
        assertEquals(1, receipts.get(),
                "notification-drain receipt must publish exactly once after waiter exits");

        future.whenNotificationsDrainedRuntime(receipts::incrementAndGet);
        assertEquals(2, receipts.get(),
                "late host drain subscriber receives a separate immediate receipt");
    }
}
