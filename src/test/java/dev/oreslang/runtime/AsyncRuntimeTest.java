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
    void taskExitReceiptWaitsForPhysicalTurnAndCannotBeCancelled() throws Exception {
        CountDownLatch turnLeaving = new CountDownLatch(1);
        CountDownLatch allowPhysicalExit = new CountDownLatch(1);

        try (AsyncRuntime runtime = new AsyncRuntime(turn -> {
            try {
                turn.run();
            } finally {
                turnLeaving.countDown();
                boolean interrupted = false;
                for (;;) {
                    try {
                        allowPhysicalExit.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        })) {
            OresFuture<Integer> task = runtime.submit(() -> 7);
            assertTrue(turnLeaving.await(2, TimeUnit.SECONDS));
            assertFalse(task.isDone(),
                    "successful task completion is not published before context-exit");
            OresFuture<Void> exit = runtime.taskExitReceipt(task);
            assertFalse(exit.isDone(),
                    "physical-exit receipt settled while TurnExecutor still owned the guest turn");
            assertFalse(exit.cancel(true),
                    "physical-exit receipt must be an irrevocable runtime observation");
            // Cancellation may settle the exposed Future before the context
            // adapter has left its physical turn. The uncancellable exit
            // receipt must remain pending through that gap.
            assertTrue(task.cancel(true));
            assertTrue(task.isCancelled());
            assertFalse(exit.isDone(),
                    "logical cancellation must not fake physical guest exit");

            allowPhysicalExit.countDown();
            assertNull(exit.get(2, TimeUnit.SECONDS));
            assertFalse(exit.isCancelled());
        } finally {
            allowPhysicalExit.countDown();
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
    void concurrentHostWaitsDoNotPreventIndependentAsyncProgress() throws Exception {
        // Compatibility virtual threads, not native Oreslang guest lowering.
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            int count = 12;
            CountDownLatch waiting = new CountDownLatch(count);
            CountDownLatch release = new CountDownLatch(1);
            java.util.ArrayList<OresFuture<Integer>> blocked = new java.util.ArrayList<>();
            Thread producer = Thread.currentThread();
            try {
                for (int i = 0; i < count; i++) {
                    final int index = i;
                    blocked.add(runtime.submit(() -> {
                        assertNotSame(producer, Thread.currentThread());
                        assertTrue(AsyncRuntime.isAsyncCarrierThread());
                        assertFalse(ActorRuntime.isActorCarrierThread());
                        waiting.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("host wait did not resume");
                        }
                        return index;
                    }));
                }
                assertTrue(waiting.await(5, TimeUnit.SECONDS));
                assertEquals(999, AsyncRuntime.await(runtime.submit(() -> 999)),
                        "unrelated work must progress while other tasks wait");
            } finally {
                release.countDown();
            }
            for (int i = 0; i < count; i++) {
                assertEquals(i, AsyncRuntime.await(blocked.get(i)));
            }
        }
    }

    @Test
    void cancelledHostTaskCannotPublishSuccessfulResultAfterRelease() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            OresFuture<Integer> future = runtime.submit(() -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException cancellation) {
                    Thread.currentThread().interrupt();
                }
                return 41;
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(future.cancel(true));
            } finally {
                release.countDown();
            }
            assertTrue(future.isCancelled(), "late completion cannot revive cancelled task");
        }
    }

}