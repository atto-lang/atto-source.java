package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Async waterfall stages use one tracked Task and typed lazy callbacks. */
public class TaskWaterfallTest {
    @Test void heterogeneousFourStepWaterfallPipesResultsInOrder() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            TaskHandle<Integer> handle = TaskWaterfall.waterfall(
                    scheduler,
                    () -> OresFuture.completed(7),
                    number -> OresFuture.completed("id-" + number),
                    value -> OresFuture.completed(value.length()),
                    length -> OresFuture.completed(length * 3));
            assertEquals(Integer.valueOf(12), handle.result().get(3, TimeUnit.SECONDS));
            assertEquals(new TaskHandle.Success<>(12),
                    handle.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void synchronousAndAsyncStepsComposeWithoutErasingTypes() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Long> handle = TaskWaterfall.from(
                    () -> OresFuture.completed(8))
                .thenValue(v -> "n" + (v + 4))
                .then(s -> OresFuture.completed((long) s.length()))
                .start(scheduler);
            assertEquals(Long.valueOf(3), handle.result().get(3, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Success.class,
                    handle.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void noLaterStageStartsUntilPreviousFutureSucceeds() throws Exception {
        OresFuture<Integer> first = new OresFuture<>();
        OresFuture<String> second = new OresFuture<>();
        CountDownLatch firstCalled = new CountDownLatch(1);
        CountDownLatch secondCalled = new CountDownLatch(1);
        AtomicInteger thirdCalled = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.waterfall(
                    scheduler,
                    () -> {
                        firstCalled.countDown();
                        return first;
                    },
                    number -> {
                        assertEquals(Integer.valueOf(3), number);
                        secondCalled.countDown();
                        return second;
                    },
                    text -> {
                        thirdCalled.incrementAndGet();
                        return OresFuture.completed(text.length());
                    });
            assertTrue(firstCalled.await(3, TimeUnit.SECONDS));
            assertEquals(1L, secondCalled.getCount());
            assertEquals(0, thirdCalled.get());
            first.completeFromRuntime(3);
            assertTrue(secondCalled.await(3, TimeUnit.SECONDS));
            assertEquals(0, thirdCalled.get());
            assertFalse(handle.result().isDone());
            second.completeFromRuntime("abcd");
            assertEquals(Integer.valueOf(4), handle.result().get(3, TimeUnit.SECONDS));
            assertEquals(1, thirdCalled.get());
            assertInstanceOf(TaskHandle.Success.class,
                    handle.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void thrownOrFailedStageStopsRemainingWork() throws Exception {
        AtomicInteger laterCalls = new AtomicInteger();
        IllegalArgumentException sourceFailure =
                new IllegalArgumentException("first step failed");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.waterfall(
                    scheduler,
                    () -> OresFuture.<Integer>failed(sourceFailure),
                    number -> {
                        laterCalls.incrementAndGet();
                        return OresFuture.completed(number + 1);
                    },
                    number -> {
                        laterCalls.incrementAndGet();
                        return OresFuture.completed(number + 1);
                    });
            assertSame(sourceFailure, assertThrows(ExecutionException.class,
                    () -> handle.result().get(3, TimeUnit.SECONDS)).getCause());
            assertEquals(0, laterCalls.get());
            TaskHandle.Failed<?> failure = assertInstanceOf(
                    TaskHandle.Failed.class, handle.getDone().get(3, TimeUnit.SECONDS));
            assertSame(sourceFailure, failure.failure());
        }
    }

    @Test void sourceCancellationRequestsWaterfallCancellationNotProducerOwnership()
            throws Exception {
        OresFuture<Integer> first = new OresFuture<>();
        CountDownLatch admitted = new CountDownLatch(1);
        AtomicInteger nextInvoked = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.waterfall(
                    scheduler,
                    () -> {
                        admitted.countDown();
                        return first;
                    },
                    v -> {
                        nextInvoked.incrementAndGet();
                        return OresFuture.completed(v + 1);
                    },
                    v -> OresFuture.completed(v + 2));
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            first.cancel(false);
            TaskHandle.TaskOutcome<Integer> outcome =
                    handle.getDone().get(3, TimeUnit.SECONDS);
            assertInstanceOf(TaskHandle.Cancelled.class, outcome);
            assertEquals(0, nextInvoked.get());
            assertEquals(0, first.pendingRuntimeWaiterCount());
        }
    }

    @Test void cancellingWaterfallDoesNotCancelBorrowedStageFuture() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.from(() -> {
                started.countDown();
                return source;
            }).then(v -> OresFuture.completed(v + 1)).start(scheduler);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(handle.cancel("owner cancelled"));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    handle.getDone().get(3, TimeUnit.SECONDS));
            assertFalse(source.isCancelled(), "waterfall only borrowed the stage producer");
            assertEquals(0, source.pendingRuntimeWaiterCount());
            source.completeFromRuntime(11);
            assertEquals(0, source.pendingRuntimeWaiterCount());
        }
    }

    @Test void prefixAndExtendedPipelineShareOneStartPermit() throws Exception {
        AtomicInteger factories = new AtomicInteger();
        TaskWaterfall.Pipeline<Integer> base = TaskWaterfall.from(() -> {
            factories.incrementAndGet();
            return OresFuture.completed(5);
        });
        TaskWaterfall.Pipeline<String> extended =
                base.thenValue(v -> "result:" + v);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<String> handle = extended.start(scheduler);
            assertEquals("result:5", handle.result().get(3, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> base.start(scheduler));
            assertThrows(IllegalStateException.class, () -> extended.start(scheduler));
            assertEquals(1, factories.get());
        }
    }

    @Test void asyncStageRunsOnSchedulerNotCompletionThread() throws Exception {
        OresFuture<Integer> first = new OresFuture<>();
        AtomicReference<Thread> producer = new AtomicReference<>();
        AtomicReference<Thread> transformer = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.waterfall(
                    scheduler,
                    () -> {
                        started.countDown();
                        return first;
                    },
                    value -> {
                        transformer.set(Thread.currentThread());
                        return OresFuture.completed(value + 9);
                    },
                    value -> OresFuture.completed(value));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            Thread publisher = Thread.ofPlatform().start(() -> {
                producer.set(Thread.currentThread());
                first.completeFromRuntime(1);
            });
            publisher.join(3_000);
            assertFalse(publisher.isAlive());
            assertEquals(Integer.valueOf(10), handle.result().get(3, TimeUnit.SECONDS));
            assertNotNull(transformer.get());
            assertNotSame(producer.get(), transformer.get());
        }
    }

    @Test void manyTypedStagesDoNotRecursivelyInvokeCallbacks() throws Exception {
        TaskWaterfall.Pipeline<Integer> chain =
                TaskWaterfall.from(() -> OresFuture.completed(0));
        final int count = 1_024;
        for (int i = 0; i < count; i++) {
            chain = chain.thenValue(v -> v + 1);
        }
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = chain.start(scheduler);
            assertEquals(Integer.valueOf(count), handle.result().get(10, TimeUnit.SECONDS));
            assertEquals(new TaskHandle.Success<>(count),
                    handle.getDone().get(10, TimeUnit.SECONDS));
        }
    }

    @Test void nullFutureStageFailsBeforeInvokingLaterStage() throws Exception {
        AtomicInteger later = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.waterfall(
                    scheduler,
                    () -> OresFuture.completed(8),
                    value -> (OresFuture<Integer>) null,
                    value -> {
                        later.incrementAndGet();
                        return OresFuture.completed(value + 1);
                    });
            assertInstanceOf(NullPointerException.class,
                    assertThrows(ExecutionException.class,
                            () -> handle.result().get(3, TimeUnit.SECONDS)).getCause());
            assertEquals(0, later.get());
            assertInstanceOf(TaskHandle.Failed.class,
                    handle.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void awaitableStagesMixWithSyncAndFutureStages() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<String> pipeline = TaskWaterfall.from(
                    () -> OresFuture.completed(5))
                .thenValue(n -> n * 2)
                .thenAwaitable(n -> (Awaitable<String>)
                        () -> OresFuture.completed("number:" + n))
                .start(scheduler);
            assertEquals("number:10", pipeline.result().get(3, TimeUnit.SECONDS));
            assertEquals(new TaskHandle.Success<>("number:10"),
                    pipeline.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void failedAwaitableProjectionStopsChainBeforeFollowingStage()
            throws Exception {
        AtomicInteger laterStages = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<String> pipeline = TaskWaterfall.from(
                    () -> OresFuture.completed(5))
                .thenAwaitable(v -> (Awaitable<String>)
                        () -> OresFuture.failed(new IllegalStateException("stage failed")))
                .thenValue(v -> {
                    laterStages.incrementAndGet();
                    return v;
                })
                .start(scheduler);
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class,
                            () -> pipeline.result().get(3, TimeUnit.SECONDS)).getCause());
            assertEquals(0, laterStages.get());
            assertInstanceOf(TaskHandle.Failed.class,
                    pipeline.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void preCancelledFirstStagePreservesOwnedTaskCancellation() throws Exception {
        OresFuture<Integer> first = new OresFuture<>();
        assertTrue(first.cancel(false));
        AtomicInteger secondCalls = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.waterfall(
                    scheduler,
                    () -> first,
                    v -> {
                        secondCalls.incrementAndGet();
                        return OresFuture.completed(v + 1);
                    },
                    v -> OresFuture.completed(v + 1));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    handle.getDone().get(3, TimeUnit.SECONDS));
            assertTrue(handle.result().isCancelled());
            assertEquals(0, secondCalls.get());
            assertEquals(0, first.pendingRuntimeWaiterCount());
        }
    }

    @Test void startupGatePreventsFactoryRunningBeforeStartReturns() throws Exception {
        // The factory must run on a scheduler carrier after the TaskHandle
        // exists, not on the calling thread as an eager argument evaluation.
        AtomicReference<Thread> factoryThread = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.from(() -> {
                factoryThread.set(Thread.currentThread());
                return OresFuture.completed(42);
            }).start(scheduler);
            assertEquals(Integer.valueOf(42), handle.result().get(3, TimeUnit.SECONDS));
            assertNotNull(factoryThread.get());
            assertNotSame(caller, factoryThread.get());
        }
    }

    @Test void userRaisedCancellationExceptionInStageIsFailureNotCancellation()
            throws Exception {
        java.util.concurrent.CancellationException raised =
                new java.util.concurrent.CancellationException("guest raised");
        AtomicInteger later = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.from(
                    () -> OresFuture.completed(2))
                .then(n -> OresFuture.<Integer>failed(raised))
                .thenValue(v -> {
                    later.incrementAndGet();
                    return v + 1;
                }).start(scheduler);
            var error = assertThrows(ExecutionException.class,
                    () -> handle.result().get(3, TimeUnit.SECONDS));
            assertSame(raised, error.getCause());
            assertFalse(handle.result().isCancelled());
            TaskHandle.Failed<?> done = assertInstanceOf(
                    TaskHandle.Failed.class,
                    handle.getDone().get(3, TimeUnit.SECONDS));
            assertSame(raised, done.failure());
            assertEquals(0, later.get());
        }
    }

    @Test void failedMiddleStagePreservesExactCauseAndStopsLaterStages()
            throws Exception {
        IllegalStateException expected = new IllegalStateException("middle failed");
        AtomicInteger startedLater = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.from(
                    () -> OresFuture.completed(2))
                .thenValue(n -> n * 2)
                .then(n -> OresFuture.<Integer>failed(expected))
                .thenValue(n -> {
                    startedLater.incrementAndGet();
                    return n + 1;
                }).start(scheduler);
            assertSame(expected, assertThrows(ExecutionException.class,
                    () -> handle.result().get(3, TimeUnit.SECONDS)).getCause());
            var done = assertInstanceOf(TaskHandle.Failed.class,
                    handle.getDone().get(3, TimeUnit.SECONDS));
            assertSame(expected, done.failure());
            assertEquals(0, startedLater.get());
        }
    }

    @Test void synchronousStageThrownFailureRetainsIdentity() throws Exception {
        IllegalArgumentException thrown = new IllegalArgumentException("bad transform");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.from(
                    () -> OresFuture.completed(5))
                .<Integer>thenValue(value -> {
                    throw thrown;
                })
                .thenValue(value -> value + 1)
                .start(scheduler);
            assertSame(thrown, assertThrows(ExecutionException.class,
                    () -> handle.result().get(3, TimeUnit.SECONDS)).getCause());
            assertSame(thrown, assertInstanceOf(TaskHandle.Failed.class,
                    handle.getDone().get(3, TimeUnit.SECONDS)).failure());
        }
    }

    @Test void hostErrorInStageIsNotSilentlyConvertedToCancellation()
            throws Exception {
        AssertionError fatal = new AssertionError("host error");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle = TaskWaterfall.from(
                    () -> OresFuture.completed(1))
                .<Integer>thenValue(n -> {
                    throw fatal;
                })
                .thenValue(v -> v + 1)
                .start(scheduler);
            assertSame(fatal, assertThrows(ExecutionException.class,
                    () -> handle.result().get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(handle.result().isCancelled());
            assertSame(fatal, assertInstanceOf(TaskHandle.Failed.class,
                    handle.getDone().get(3, TimeUnit.SECONDS)).failure());
        }
    }

    @Test void waterfallFailureContextIdentifiesMiddleStepWithoutWrapping()
            throws Exception {
        IllegalArgumentException exact = new IllegalArgumentException("bad stage");
        var pipeline = TaskWaterfall.from(() -> OresFuture.completed(5))
                .thenValue(v -> v + 2)
                .then(v -> OresFuture.<String>failed(exact))
                .thenValue(v -> v.length());
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> run = pipeline.start(scheduler);
            assertSame(exact, assertThrows(ExecutionException.class,
                    () -> run.result().get(3, TimeUnit.SECONDS)).getCause());
            assertInstanceOf(TaskHandle.Failed.class,
                    run.getDone().get(3, TimeUnit.SECONDS));
            var context = pipeline.failureContext().orElseThrow();
            assertEquals(2, context.stageIndex());
            assertSame(exact, context.cause());
            assertFalse(context.cancelled());
        }
    }
}
