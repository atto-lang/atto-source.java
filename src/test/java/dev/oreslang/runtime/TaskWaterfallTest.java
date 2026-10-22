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
}
