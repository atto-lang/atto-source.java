package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

final class OresFuturesTest {

    @Test
    void allPreservesInputOrderIndependentOfCompletionOrder() {
        CompletableFuture<Integer> first = new CompletableFuture<>();
        CompletableFuture<Integer> second = new CompletableFuture<>();
        CompletableFuture<Integer> third = new CompletableFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(first, second, third));
        third.complete(3);
        first.complete(1);
        assertFalse(all.isDone());

        second.complete(2);
        assertEquals(List.of(1, 2, 3), all.join());
    }

    @Test
    void allPropagatesFailure() {
        CompletableFuture<Integer> ok = new CompletableFuture<>();
        CompletableFuture<Integer> bad = new CompletableFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(ok, bad));
        ok.complete(1);
        bad.completeExceptionally(new IllegalStateException("boom"));

        CompletionException failure = assertThrows(CompletionException.class, all::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test
    void cancellingAggregateCancelsChildren() {
        CompletableFuture<Integer> first = new CompletableFuture<>();
        CompletableFuture<Integer> second = new CompletableFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(first, second));
        assertTrue(all.cancel(true));

        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
    }

    @Test
    void guestCannotForgeFutureCompletion() {
        OresFuture<Integer> future = new OresFuture<>();

        assertThrows(UnsupportedOperationException.class, () -> future.complete(99));
        assertThrows(
                UnsupportedOperationException.class,
                () -> future.completeExceptionally(new IllegalStateException("forged")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> future.completeOnTimeout(99, 1, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertThrows(
                UnsupportedOperationException.class,
                () -> future.orTimeout(1, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertFalse(future.isDone());
    }

    @Test
    void oresFutureDoesNotExposeCompletionStageCallbackSurface() {
        assertFalse(
                java.util.concurrent.CompletionStage.class.isAssignableFrom(
                        OresFuture.class));
        assertFalse(
                java.util.Arrays.stream(OresFuture.class.getMethods())
                        .anyMatch(method -> method.getName().equals("thenApply")
                                || method.getName().equals("thenAccept")
                                || method.getName().equals("thenRun")),
                "guest-visible OresFuture must not inherit producer-thread callback APIs");
    }

    @Test
    void runtimeWaiterIsDeliveredExactlyOnceWhenRegistrationRacesCompletion()
            throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            OresFuture<Integer> future = new OresFuture<>();
            java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger callbacks =
                    new java.util.concurrent.atomic.AtomicInteger();

            Thread registrar = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                future.whenCompleteRuntime((value, failure) -> {
                    assertNull(failure);
                    assertEquals(7, value);
                    callbacks.incrementAndGet();
                });
            });
            Thread completer = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                future.completeFromRuntime(7);
            });

            start.countDown();
            registrar.join();
            completer.join();

            assertEquals(7, future.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, callbacks.get(),
                    "completion/registration race must not duplicate a waiter");
        }
    }

    @Test
    void raceCompletesWithFirstCompletion() {
        CompletableFuture<Integer> slow = new CompletableFuture<>();
        CompletableFuture<Integer> fast = new CompletableFuture<>();

        OresFuture<Integer> race = OresFutures.race(List.of(slow, fast));
        fast.complete(7);

        assertEquals(7, race.join());
    }
    @Test
    void raceDetachesLoserWithoutCancellingProducer() {
        OresFuture<Integer> slow = new OresFuture<>();
        OresFuture<Integer> fast = new OresFuture<>();

        OresFuture<Integer> race = OresFutures.race(List.of(slow, fast));
        assertEquals(1, slow.queuedRuntimeWaiters());
        assertEquals(1, fast.queuedRuntimeWaiters());

        assertTrue(fast.completeFromRuntime(7));
        assertEquals(7, race.join());

        assertEquals(0, slow.queuedRuntimeWaiters(),
                "completed race must detach the losing Future callback");
        assertFalse(slow.isCancelled(),
                "normal race completion must not cancel the losing producer");
        assertTrue(slow.completeFromRuntime(9),
                "detached loser must remain independently usable");
    }

    @Test
    void failedAllDetachesPendingChildrenWithoutCancellingThem() {
        OresFuture<Integer> pending = new OresFuture<>();
        OresFuture<Integer> failed = new OresFuture<>();

        OresFuture<List<Integer>> all = OresFutures.all(List.of(pending, failed));
        assertEquals(1, pending.queuedRuntimeWaiters());
        assertEquals(1, failed.queuedRuntimeWaiters());

        assertTrue(failed.failFromRuntime(new IllegalStateException("boom")));

        CompletionException failure =
                assertThrows(CompletionException.class, all::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(0, pending.queuedRuntimeWaiters(),
                "failed all() must stop retaining unrelated pending children");
        assertFalse(pending.isCancelled(),
                "child cancellation remains reserved for explicit aggregate cancellation");
        assertTrue(pending.completeFromRuntime(1));
    }

    @Test
    void cancellingAllSettledDetachesAndCancelsPendingChildren() {
        OresFuture<Integer> first = new OresFuture<>();
        OresFuture<Integer> second = new OresFuture<>();

        OresFuture<List<OresFutures.Settled<Integer>>> all =
                OresFutures.allSettled(List.of(first, second));
        assertEquals(1, first.queuedRuntimeWaiters());
        assertEquals(1, second.queuedRuntimeWaiters());

        assertTrue(all.cancel(true));

        assertEquals(0, first.queuedRuntimeWaiters());
        assertEquals(0, second.queuedRuntimeWaiters());
        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
    }

}
