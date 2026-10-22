package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class TaskPipelineTest {
    @Test
    void executesDeferredMixedStagesInOneSchedulerTaskDomain() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        OresFuture<Integer> nested = new OresFuture<>();
        List<String> order = new ArrayList<>();
        AtomicReference<Object> taskDomain = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> output = TaskPipeline.start(scheduler, source,
                    List.of(
                            value -> {
                                assertSame(scheduler, OresScheduler.current());
                                assertTrue(OresScheduler.currentDispatchId() > 0);
                                taskDomain.set(OresScheduler.currentTaskDomain());
                                order.add("validate");
                                calls.incrementAndGet();
                                return ((Integer) value) + 1;
                            },
                            value -> {
                                assertSame(taskDomain.get(), OresScheduler.currentTaskDomain());
                                order.add("save");
                                calls.incrementAndGet();
                                assertEquals(21, value);
                                return nested;
                            },
                            value -> {
                                assertSame(taskDomain.get(), OresScheduler.currentTaskDomain());
                                order.add("notify");
                                calls.incrementAndGet();
                                return ((Integer) value) + 1;
                            }));
            assertTrue(order.isEmpty(), "stages must not run before upstream completion");
            assertTrue(source.completeFromRuntime(20));
            awaitWaiter(nested);
            assertEquals(List.of("validate", "save"), order);
            assertTrue(nested.completeFromRuntime(41));
            assertEquals(42, output.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("validate", "save", "notify"), order);
            assertEquals(3, calls.get(), "every pipeline stage executes exactly once");
        }
        assertEquals(0, source.pendingRuntimeWaiterCount());
        assertEquals(0, nested.pendingRuntimeWaiterCount());
    }

    @Test
    void upstreamFailureSkipsLaterStageArgumentsAndSideEffects() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> output = TaskPipeline.start(scheduler, source,
                    List.of(value -> {
                        invoked.incrementAndGet(); // includes delayed stage arguments
                        return value;
                    }));
            assertTrue(source.failFromRuntime(new IllegalStateException("before-save")));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> output.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("before-save", failure.getCause().getMessage());
            assertEquals(0, invoked.get());
        }
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }

    @Test
    void nestedFailureSkipsSubsequentStageAndPreservesSharedInput() throws Exception {
        OresFuture<Integer> source = OresFuture.completed(1);
        OresFuture<Integer> nested = new OresFuture<>();
        AtomicInteger calls = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> output = TaskPipeline.start(scheduler, source,
                    List.of(
                            value -> {
                                calls.incrementAndGet();
                                return nested;
                            },
                            value -> {
                                calls.incrementAndGet();
                                return value;
                            }));
            awaitWaiter(nested);
            assertTrue(nested.failFromRuntime(new IllegalArgumentException("save failed")));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> output.get(5, TimeUnit.SECONDS));
            assertEquals("save failed", failure.getCause().getMessage());
            assertEquals(1, calls.get());
            assertFalse(source.isCancelled());
        }
    }

    @Test
    void cancelledSourceCancelsPipelineInsteadOfForgingOrdinaryFailure() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        AtomicBoolean called = new AtomicBoolean();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> output = TaskPipeline.start(scheduler, source,
                    List.of(value -> {
                        called.set(true);
                        return value;
                    }));
            awaitWaiter(source);
            assertTrue(source.cancel(false));
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> output.get(5, TimeUnit.SECONDS));
            assertTrue(output.isCancelled());
            assertFalse(called.get());
        }
    }

    @Test
    void cancellingPipelineDetachesButDoesNotCancelSharedProducer() throws Exception {
        OresFuture<Integer> shared = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> output = TaskPipeline.start(scheduler, shared,
                    List.of(value -> ((Integer) value) + 1));
            awaitWaiter(shared);
            assertTrue(output.cancel(false));
            assertTrue(output.isCancelled());
            assertFalse(shared.isCancelled());
            waitForNoWaiter(shared);
            assertTrue(shared.completeFromRuntime(10));
            assertEquals(0, shared.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void cancellingPipelineCancelsExplicitlyOwnedStageOnly() throws Exception {
        OresFuture<Integer> shared = OresFuture.completed(10);
        OresFuture<Integer> child = new OresFuture<>();
        CountDownLatch admitted = new CountDownLatch(1);
        AtomicInteger later = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> output = TaskPipeline.start(scheduler, shared,
                    List.of(value -> {
                        admitted.countDown();
                        return TaskPipeline.owned(child);
                    }, value -> {
                        later.incrementAndGet();
                        return value;
                    }));
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            assertTrue(output.cancel(false));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!child.isCancelled() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(child.isCancelled(), "owned work must be cancelled even when admission races revocation");
            assertFalse(shared.isCancelled(), "shared upstream must not be cancelled");
            assertEquals(0, later.get());
        }
    }

    @Test
    void cancellingPipelineDoesNotCancelSharedStageFuture() throws Exception {
        OresFuture<Integer> sharedChild = new OresFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> output = TaskPipeline.start(scheduler,
                    OresFuture.completed(1),
                    List.of(value -> sharedChild,
                            value -> {
                                invoked.incrementAndGet();
                                return value;
                            }));
            awaitWaiter(sharedChild);
            assertTrue(output.cancel(false));
            assertFalse(sharedChild.isCancelled(),
                    "unowned stage Future may be shared with other consumers");
            waitForNoWaiter(sharedChild);
            assertEquals(0, invoked.get());
            assertTrue(sharedChild.completeFromRuntime(42));
        }
    }

    @Test
    void repeatedCompletionAndCancellationRacesNeverCancelSharedProducer()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int iteration = 0; iteration < 64; iteration++) {
                OresFuture<Integer> producer = new OresFuture<>();
                OresFuture<Integer> output = TaskPipeline.start(scheduler, producer,
                        List.of(value -> ((Integer) value) + 1));
                assertTrue(output.cancel(false));
                assertFalse(producer.isCancelled(), "iteration " + iteration);
                assertTrue(producer.completeFromRuntime(iteration));
                assertTrue(output.isCancelled());
                waitForNoWaiter(producer);
            }
        }
    }

    @Test
    void voidOutputAndEmptyPipelinePreserveNullCompletion() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> output = TaskPipeline.start(scheduler,
                    OresFuture.completed(null), List.of());
            assertNull(output.get(5, TimeUnit.SECONDS));
            OresFuture<Void> mapped = TaskPipeline.start(scheduler,
                    OresFuture.completed(1), List.of(value -> null));
            assertNull(mapped.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void alreadySettledSourceStillInvokesStageOnOwningScheduler() throws Exception {
        Thread producer = Thread.currentThread();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> output = TaskPipeline.start(scheduler,
                    OresFuture.completed(41),
                    List.of(value -> {
                        callbackThread.set(Thread.currentThread());
                        assertSame(scheduler, OresScheduler.current());
                        assertNotEquals(0L, OresScheduler.currentDispatchId());
                        return ((Integer) value) + 1;
                    }));
            assertEquals(42, output.get(5, TimeUnit.SECONDS));
            assertNotSame(producer, callbackThread.get());
        }
    }

    @Test
    void rejectsNullStageAndUnboundedPipeline() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> input = OresFuture.completed(1);
            assertThrows(NullPointerException.class,
                    () -> TaskPipeline.start(scheduler, input, java.util.Arrays.asList(
                            (TaskPipeline.Stage) value -> value, null)));
            List<TaskPipeline.Stage> stages = new ArrayList<>();
            for (int i = 0; i < 129; i++) stages.add(value -> value);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TaskPipeline.start(scheduler, input, stages));
            assertTrue(failure.getMessage().contains("128 stages"));
        }
    }

    private static void awaitWaiter(OresFuture<?> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (future.pendingRuntimeWaiterCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertEquals(1, future.pendingRuntimeWaiterCount());
    }

    private static void waitForNoWaiter(OresFuture<?> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (future.pendingRuntimeWaiterCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertEquals(0, future.pendingRuntimeWaiterCount());
    }
}
