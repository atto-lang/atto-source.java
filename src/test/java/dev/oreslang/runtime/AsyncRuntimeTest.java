package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class AsyncRuntimeTest {
    @Test
    void submitReturnsComposableFutureAndDoesNotUseActorDispatcher() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            OresFuture<Integer> future = runtime.submit(() -> {
                assertTrue(AsyncRuntime.isAsyncCarrierThread());
                assertFalse(ActorRuntime.isActorCarrierThread());
                started.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return 42;
            });

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(future.isDone());
            release.countDown();
            assertEquals(42, AsyncRuntime.await(future));
        }
    }

    @Test
    void cancellationInterruptsBackingVirtualTask() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);

            OresFuture<Integer> future = runtime.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                    return 1;
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    throw expected;
                }
            });

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(future.isCancelled());
        }
    }

    @Test
    void awaitPropagatesOriginalRuntimeFailure() {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        IllegalStateException original = new IllegalStateException("boom");
        future.completeExceptionally(original);

        IllegalStateException observed =
                assertThrows(IllegalStateException.class, () -> AsyncRuntime.await(future));
        assertSame(original, observed);
    }

    @Test
    void closeRejectsNewTasks() {
        AsyncRuntime runtime = new AsyncRuntime();
        runtime.close();
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> runtime.submit(() -> 1));
    }
    @Test
    void submitReturnsOresFutureRatherThanCompletableFuture() {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            OresFuture<Integer> future = runtime.submit(() -> 7);
            assertEquals(OresFuture.class, future.getClass());
            assertEquals(7, AsyncRuntime.await(future));
        }
    }

    @Test
    void hostCompletionStageIsOneWayAdaptedIntoOresFuture() {
        CompletableFuture<Integer> host = new CompletableFuture<>();
        OresFuture<Integer> ores = OresFuture.from(host);

        host.complete(9);

        assertEquals(9, AsyncRuntime.await(ores));
    }


    @Test
    void failedAsyncCloseMustWaitForActualCarrierExitOnRetry() throws Exception {
        AsyncRuntime runtime = new AsyncRuntime();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger exits = new java.util.concurrent.atomic.AtomicInteger();
        try {
            OresFuture<Integer> future = runtime.submit(() -> {
                entered.countDown();
                try {
                    while (release.getCount() != 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // An uncooperative host operation cannot be retired early.
                        }
                    }
                    return 7;
                } finally {
                    exits.incrementAndGet();
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            IllegalStateException incomplete = assertThrows(
                    IllegalStateException.class, runtime::close);
            assertTrue(incomplete.getMessage().contains("did not terminate"),
                    incomplete.getMessage());
            assertTrue(runtime.isClosed());
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> runtime.submit(() -> 1));
            assertEquals(0, exits.get(), "timeout must not imply physical exit");
            release.countDown();
            assertDoesNotThrow(runtime::close,
                    "retry must wait for the actual virtual carrier exit");
            assertEquals(1, exits.get());
            assertTrue(future.isDone() || runtime.isClosed());
        } finally {
            release.countDown();
            runtime.close();
        }
    }
}