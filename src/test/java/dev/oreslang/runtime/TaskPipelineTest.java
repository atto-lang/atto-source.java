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
    void concurrentProducerSettlementAndPipelineCancellationDoNotDuplicateStages()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int iteration = 0; iteration < 64; iteration++) {
                OresFuture<Integer> producer = new OresFuture<>();
                AtomicInteger stageCalls = new AtomicInteger();
                OresFuture<Integer> output = TaskPipeline.start(scheduler, producer,
                        List.of(value -> {
                            stageCalls.incrementAndGet();
                            return ((Integer) value) + 1;
                        }));
                CountDownLatch go = new CountDownLatch(1);
                AtomicReference<Throwable> producerFailure = new AtomicReference<>();
                Thread completer = Thread.startVirtualThread(() -> {
                    try {
                        if (!go.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("race gate did not open");
                        }
                        assertTrue(producer.completeFromRuntime(41));
                    } catch (Throwable failure) {
                        producerFailure.set(failure);
                    }
                });
                go.countDown();
                output.cancel(false);
                completer.join(5_000);
                assertFalse(completer.isAlive());
                assertNull(producerFailure.get(), () -> String.valueOf(producerFailure.get()));
                if (!output.isCancelled()) {
                    assertEquals(42, output.get(5, TimeUnit.SECONDS));
                }
                assertTrue(stageCalls.get() <= 1, "a pipeline stage ran twice");
                assertFalse(producer.isCancelled());
                waitForNoWaiter(producer);
            }
        }
    }

    @Test
    void trackedPipelineDoneWaitsForPhysicalStageUnwindOnCancellation()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskPipeline.PipelineHandle<Integer> handle = TaskPipeline.startTracked(
                    scheduler, OresFuture.completed(1),
                    List.of(value -> {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("blocked stage did not resume");
                        }
                        return ((Integer) value) + 1;
                    }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                assertTrue(handle.cancel());
                assertTrue(handle.result().isCancelled(),
                        "result cancellation is visible before physical retirement");
                assertFalse(handle.getDone().isDone(),
                        "running physical task must not claim quiescence");
            } finally {
                release.countDown();
            }
            TaskHandle.TaskOutcome<Integer> retired =
                    handle.getDone().get(5, TimeUnit.SECONDS);
            assertInstanceOf(TaskHandle.Cancelled.class, retired);
        }
    }

    @Test
    void cancellingOwningTaskScopeCancelsPipelineButNotBorrowedInput()
            throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            TaskPipeline.PipelineHandle<Integer> handle =
                    TaskPipeline.startInScope(scope, source,
                            List.of(value -> ((Integer) value) + 1));
            awaitWaiter(source);
            assertTrue(scope.cancel("owner stopped"));
            assertNull(scope.getDone().get(5, TimeUnit.SECONDS),
                    "scope completion is a separate retirement witness");
            assertInstanceOf(TaskHandle.Cancelled.class,
                    handle.getDone().get(5, TimeUnit.SECONDS));
            assertTrue(handle.result().isCancelled());
            assertFalse(source.isCancelled(), "shared producer must survive scope cancellation");
            waitForNoWaiter(source);
            assertTrue(source.completeFromRuntime(17));
        }
    }

    @Test
    void sealedScopeRejectsPipelineWithoutChangingInputFuture() {
        OresFuture<Integer> input = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            scope.seal();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> TaskPipeline.startInScope(scope, input, List.of(v -> v)));
            assertFalse(input.isDone());
            assertEquals(0, input.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void returningPipelineOwnCompletionDoesNotDeadlockTheAwaitGraph()
            throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        AtomicReference<OresFuture<Integer>> own = new AtomicReference<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> output = TaskPipeline.start(scheduler, source,
                    List.of(value -> own.get()));
            own.set(output);
            awaitWaiter(source);
            assertTrue(source.completeFromRuntime(1));
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> output.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("own exposed completion"),
                    error::toString);
        }
    }

    @Test
    void transferringPipelineOwnCompletionIsRejectedBeforeOwnershipRegistration()
            throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        AtomicReference<OresFuture<Integer>> own = new AtomicReference<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> output = TaskPipeline.start(scheduler, source,
                    List.of(value -> TaskPipeline.owned(own.get())));
            own.set(output);
            awaitWaiter(source);
            assertTrue(source.completeFromRuntime(1));
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> output.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("own exposed completion"),
                    error::toString);
        }
    }

    @Test
    void stageCannotClaimBorrowedPipelineInputAsOwned() throws Exception {
        OresFuture<Integer> shared = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> output = TaskPipeline.start(scheduler, shared,
                    List.of(value -> TaskPipeline.owned(shared)));
            awaitWaiter(shared);
            assertTrue(shared.completeFromRuntime(1));
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> output.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("borrowed input Future"),
                    error::toString);
            assertFalse(shared.isCancelled());
        }
    }

    @Test
    void mutuallyAwaitingPipelineBridgesFailInsteadOfDeadlocking() throws Exception {
        OresFuture<Integer> seedA = new OresFuture<>();
        OresFuture<Integer> seedB = new OresFuture<>();
        AtomicReference<OresFuture<Integer>> aRef = new AtomicReference<>();
        AtomicReference<OresFuture<Integer>> bRef = new AtomicReference<>();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> a = TaskPipeline.start(scheduler, seedA,
                    List.of(value -> bRef.get()));
            OresFuture<Integer> b = TaskPipeline.start(scheduler, seedB,
                    List.of(value -> aRef.get()));
            aRef.set(a);
            bRef.set(b);
            awaitWaiter(seedA);
            awaitWaiter(seedB);
            assertTrue(seedA.completeFromRuntime(1));
            assertTrue(seedB.completeFromRuntime(2));
            ExecutionException aFailure = assertThrows(ExecutionException.class,
                    () -> a.get(5, TimeUnit.SECONDS));
            ExecutionException bFailure = assertThrows(ExecutionException.class,
                    () -> b.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, aFailure.getCause());
            assertInstanceOf(IllegalStateException.class, bFailure.getCause());
            assertTrue(aFailure.getCause().getMessage().contains("cyclic pipeline")
                    || bFailure.getCause().getMessage().contains("cyclic pipeline"));
            waitForNoPipelineEdges();
        }
    }

    @Test
    void completedAndCancelledPipelineAwaitEdgesAreRetired() throws Exception {
        OresFuture<Integer> seed = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> done = TaskPipeline.start(scheduler,
                    OresFuture.completed(20),
                    List.of(value -> ((Integer) value) + 1));
            assertEquals(21, done.get(5, TimeUnit.SECONDS));
            OresFuture<Integer> stopped = TaskPipeline.start(scheduler, seed,
                    List.of(value -> ((Integer) value) + 1));
            awaitWaiter(seed);
            assertTrue(stopped.cancel(false));
            waitForNoWaiter(seed);
            waitForNoPipelineEdges();
        }
    }

    private static void waitForNoPipelineEdges() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (TaskPipeline.pendingPipelineAwaitCount() != 0
                && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertEquals(0, TaskPipeline.pendingPipelineAwaitCount(),
                "terminal pipelines retained live await dependencies");
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
