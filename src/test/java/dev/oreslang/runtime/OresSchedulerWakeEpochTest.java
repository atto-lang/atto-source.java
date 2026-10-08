package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/** Regression for stale producer callbacks racing a *later* await epoch. */
final class OresSchedulerWakeEpochTest {

    @SuppressWarnings("unchecked")
    private static BiConsumer<Object, Throwable> pendingCallback(OresFuture<?> future)
            throws ReflectiveOperationException {
        Field waitersField = OresFuture.class.getDeclaredField("waiters");
        waitersField.setAccessible(true);
        Queue<?> waiters = (Queue<?>) waitersField.get(future);
        Object registration = waiters.peek();
        assertNotNull(registration, "await must have a pending runtime waiter");
        Field callbackField = registration.getClass().getDeclaredField("callback");
        callbackField.setAccessible(true);
        return (BiConsumer<Object, Throwable>) callbackField.get(registration);
    }

    private static void awaitWaiter(OresFuture<?> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (future.pendingRuntimeWaiterCount() != 1 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(1, future.pendingRuntimeWaiterCount());
    }

    @Test
    void oldAwaitCallbackCannotSettleTheNextAwaitOrPoisonItsTask() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> firstGate = new OresFuture<>();
            OresFuture<Integer> secondGate = new OresFuture<>();
            AtomicInteger steps = new AtomicInteger();

            OresFuture<Integer> result = scheduler.start(resume -> {
                int pc = steps.getAndIncrement();
                if (pc == 0) return OresScheduler.await(firstGate);
                if (pc == 1) {
                    assertEquals(11, resume.value());
                    return OresScheduler.await(secondGate);
                }
                assertEquals(22, resume.value());
                return OresScheduler.done((Integer) resume.value());
            });

            awaitWaiter(firstGate);
            BiConsumer<Object, Throwable> obsolete = pendingCallback(firstGate);
            firstGate.completeFromRuntime(11);
            awaitWaiter(secondGate);

            // This callback was already claimed by the real first completion.
            // A producer/queue that retained it must not corrupt a later epoch.
            obsolete.accept(999, null);
            obsolete.accept(null, new IllegalStateException("old failure"));
            assertFalse(result.isDone(), "stale epoch cannot settle the task");
            assertEquals(1, secondGate.pendingRuntimeWaiterCount());

            secondGate.completeFromRuntime(22);
            assertEquals(22, result.get(5, TimeUnit.SECONDS));
            assertEquals(3, steps.get());
        }
    }

    @Test
    void cancelRevokesOutstandingEpochEvenIfCapturedProducerCallbackRunsLate()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> pending = new OresFuture<>();
            AtomicInteger steps = new AtomicInteger();
            OresFuture<Integer> result = scheduler.start(resume -> {
                steps.incrementAndGet();
                return OresScheduler.await(pending);
            });
            awaitWaiter(pending);
            BiConsumer<Object, Throwable> obsolete = pendingCallback(pending);

            assertTrue(result.cancel(false));
            assertTrue(result.isCancelled());
            assertEquals(0, pending.pendingRuntimeWaiterCount());

            obsolete.accept(123, null);
            assertTrue(result.isCancelled());
            assertEquals(1, steps.get());
            assertFalse(pending.isDone(), "canceling an awaiter cannot cancel a shared producer");
        }
    }
}
