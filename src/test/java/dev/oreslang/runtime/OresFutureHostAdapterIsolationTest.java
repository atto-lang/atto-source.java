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
    void misleadingForeignCancellationStateCannotEraseOrdinaryHostFailure() {
        // An arbitrary foreign Future may have an inconsistent or buggy
        // cancellation probe. A true flag plus a non-cancellation exception
        // must NOT convert an actual failure into a cancellation result.
        CompletableFuture<Integer> misleading = new CompletableFuture<>() {
            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        OresFuture<Integer> observer = OresFuture.from(misleading);
        IllegalStateException expected = new IllegalStateException("host failure");
        assertTrue(misleading.completeExceptionally(expected));
        assertTrue(observer.isDone());
        assertFalse(observer.isCancelled());
        CompletionException observed =
                assertThrows(CompletionException.class, observer::join);
        assertSame(expected, observed.getCause());
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
    void observerCancellationRacingHostCompletionNeverCancelsBorrowedProducer()
            throws Exception {
        for (int iteration = 0; iteration < 128; iteration++) {
            CompletableFuture<Integer> host = new CompletableFuture<>();
            OresFuture<Integer> observer = OresFuture.from(host);
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean cancelWon =
                    new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<Throwable> threadFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();

            Thread canceller = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    cancelWon.set(observer.cancel(false));
                } catch (Throwable error) {
                    threadFailure.compareAndSet(null, error);
                }
            });
            Thread completer = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    host.complete(42);
                } catch (Throwable error) {
                    threadFailure.compareAndSet(null, error);
                }
            });
            start.countDown();
            canceller.join();
            completer.join();

            assertNull(threadFailure.get(), "both racing threads must finish cleanly");
            assertEquals(42, host.join());
            assertFalse(host.isCancelled(), "the host stage stays independently live");
            assertTrue(observer.isDone());
            if (cancelWon.get()) {
                assertTrue(observer.isCancelled());
                assertThrows(CancellationException.class, observer::join);
            } else {
                assertFalse(observer.isCancelled());
                assertEquals(42, observer.join());
            }
        }
    }

    @Test
    void aggregateCancellationRacingChildSettlementOnlyDetachesBorrowedWaiters()
            throws Exception {
        for (int iteration = 0; iteration < 96; iteration++) {
            OresFuture<Integer> first = new OresFuture<>();
            OresFuture<Integer> second = new OresFuture<>();
            OresFuture<java.util.List<Integer>> observed =
                    OresFutures.all(java.util.List.of(first, second));
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> threadFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();

            Thread cancel = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (!observed.cancel(false)) {
                        throw new AssertionError("aggregate is pending until both children settle");
                    }
                } catch (Throwable error) {
                    threadFailure.compareAndSet(null, error);
                }
            });
            Thread settle = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    first.completeFromRuntime(11);
                } catch (Throwable error) {
                    threadFailure.compareAndSet(null, error);
                }
            });
            start.countDown();
            cancel.join();
            settle.join();

            assertNull(threadFailure.get());
            assertTrue(observed.isCancelled());
            assertEquals(0, first.pendingRuntimeWaiterCount(),
                    "cancelled aggregate must detach from settled child");
            assertEquals(0, second.pendingRuntimeWaiterCount(),
                    "cancelled aggregate must detach from pending child");
            assertFalse(first.isCancelled());
            assertFalse(second.isCancelled(),
                    "aggregate observer cannot cancel a borrowed sibling");
            assertTrue(second.completeFromRuntime(12));
            assertEquals(11, first.join());
            assertEquals(12, second.join());
        }
    }

    @Test
    void foreignStageRetainingACompletionCallbackCannotReviveACancelledObserver() {
        // A generic CompletionStage has no mandatory unregister API. This
        // deliberately adversarial stage keeps its callback even after the
        // Oreslang observer is cancelled, then invokes that callback late.
        final class RetainingStage extends CompletableFuture<Integer> {
            private java.util.function.BiConsumer<? super Integer, ? super Throwable>
                    retainedCompletion;

            @Override
            public CompletableFuture<Integer> whenComplete(
                    java.util.function.BiConsumer<? super Integer, ? super Throwable>
                            completion) {
                retainedCompletion = completion;
                return this;
            }

            void signalLateCompletion(int value) {
                assertNotNull(retainedCompletion);
                retainedCompletion.accept(value, null);
            }
        }

        RetainingStage stage = new RetainingStage();
        OresFuture<Integer> observer = OresFuture.from(stage);
        assertTrue(observer.cancel(false));
        assertFalse(stage.isCancelled());
        stage.signalLateCompletion(42);

        assertTrue(observer.isCancelled());
        assertThrows(CancellationException.class, observer::join);
        assertTrue(stage.complete(42), "retained host stage is independently live");
        assertEquals(42, stage.join());
    }

    @Test
    void completedHostStageCanBeAdaptedSustainablyWithoutCallerScheduler() {
        OresFuture<Integer> adapted =
                OresFuture.from(CompletableFuture.completedFuture(42));
        assertEquals(42, adapted.join());
        assertFalse(adapted.isCancelled());
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
