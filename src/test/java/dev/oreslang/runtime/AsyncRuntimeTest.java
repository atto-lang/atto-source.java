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
    void failedCloseRetryMustWaitForPhysicalAsyncCarrierExit() throws Exception {
        AsyncRuntime runtime = new AsyncRuntime();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch physicallyExited = new CountDownLatch(1);
        try {
            runtime.submit(() -> {
                entered.countDown();
                try {
                    // Deliberately ignore shutdownNow interruption until the
                    // host releases this task's physical execution lease.
                    boolean released = false;
                    while (!released) {
                        try {
                            released = release.await(25, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // An interrupted Future is not a physical exit.
                        }
                    }
                } finally {
                    physicallyExited.countDown();
                }
                return 42;
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            IllegalStateException incomplete = assertThrows(
                    IllegalStateException.class, runtime::close);
            assertTrue(incomplete.getMessage().contains("did not terminate"),
                    () -> "unexpected first-close failure: " + incomplete);
            assertTrue(runtime.isClosed(), "new admissions must remain fenced");
            assertEquals(1L, physicallyExited.getCount(),
                    "timeout cannot certify the running task as retired");
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> runtime.submit(() -> 7));
        } finally {
            release.countDown();
        }
        assertTrue(physicallyExited.await(3, TimeUnit.SECONDS));
        assertDoesNotThrow(runtime::close,
                "successful retry must verify that the previously live carrier exited");
        assertDoesNotThrow(runtime::close, "completed close must be idempotent");
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

}