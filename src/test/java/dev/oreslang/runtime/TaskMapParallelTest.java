package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** TaskMapParallel admits lazy Tasks, holding credits through physical retirement. */
public class TaskMapParallelTest {
    private static <R> OresScheduler.Task<R> awaitValue(OresFuture<R> source) {
        return new OresScheduler.Task<>() {
            private boolean started;
            @Override
            public OresScheduler.Step<R> resume(OresScheduler.Resume resume)
                    throws Exception {
                if (!started) {
                    started = true;
                    return OresScheduler.await(source);
                }
                if (resume.failure() != null) {
                    Throwable failure = OresFuture.unwrap(resume.failure());
                    if (failure instanceof Exception exception) throw exception;
                    if (failure instanceof Error error) throw error;
                    throw new IllegalStateException(failure);
                }
                @SuppressWarnings("unchecked")
                R result = (R) resume.value();
                return OresScheduler.done(result);
            }
        };
    }

    @Test void emptyInputReturnsEmptyAndPhysicallyQuiescent() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            var handle = TaskMapParallel.<Integer, Integer>mapLimit(
                    scheduler, List.of(), 4,
                    item -> { throw new AssertionError("factory on empty"); });
            assertEquals(List.of(), handle.result().get(3, TimeUnit.SECONDS));
            assertNull(handle.getDone().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void orderedOutputWithOutOfOrderChildCompletion() throws Exception {
        OresFuture<String> a = new OresFuture<>();
        OresFuture<String> b = new OresFuture<>();
        OresFuture<String> c = new OresFuture<>();
        List<OresFuture<String>> sources = List.of(a, b, c);
        try (OresScheduler scheduler = new OresScheduler(3)) {
            var handle = TaskMapParallel.mapLimit(
                    scheduler, List.of(0, 1, 2), 3,
                    index -> awaitValue(sources.get(index)));
            c.completeFromRuntime("c");
            b.completeFromRuntime("b");
            assertFalse(handle.result().isDone());
            a.completeFromRuntime("a");
            assertEquals(List.of("a", "b", "c"),
                    handle.result().get(4, TimeUnit.SECONDS));
            assertNull(handle.getDone().get(4, TimeUnit.SECONDS));
        }
    }

    @Test void concurrencyCreditsAreRecycledOnlyAfterQuiescence() throws Exception {
        final int n = 12;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        @SuppressWarnings("unchecked")
        OresFuture<Integer>[] completions = new OresFuture[n];
        for (int i = 0; i < n; i++) completions[i] = new OresFuture<>();
        CountDownLatch firstWave = new CountDownLatch(3);
        try (OresScheduler scheduler = new OresScheduler(4)) {
            var handle = TaskMapParallel.mapLimit(
                    scheduler, List.of(0,1,2,3,4,5,6,7,8,9,10,11), 3,
                    index -> new OresScheduler.Task<Integer>() {
                        private boolean started;
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume resume) {
                            if (!started) {
                                started = true;
                                int currently = active.incrementAndGet();
                                peak.accumulateAndGet(currently, Math::max);
                                if (index < 3) firstWave.countDown();
                                return OresScheduler.await(completions[index]);
                            }
                            active.decrementAndGet();
                            return OresScheduler.done(index * 2);
                        }
                    });
            assertTrue(firstWave.await(3, TimeUnit.SECONDS));
            assertEquals(3, active.get());
            for (int i = 0; i < n; i++) {
                completions[i].completeFromRuntime(i * 2);
            }
            assertEquals(List.of(0,2,4,6,8,10,12,14,16,18,20,22),
                    handle.result().get(5, TimeUnit.SECONDS));
            assertNull(handle.getDone().get(5, TimeUnit.SECONDS));
            assertTrue(peak.get() <= 3);
            assertEquals(0, active.get());
        }
    }

    @Test void seriesDoesNotStartSecondTaskUntilFirstRetires() throws Exception {
        OresFuture<Integer> first = new OresFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger secondFactory = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            var handle = TaskMapParallel.mapSeries(scheduler, List.of(1,2),
                    item -> {
                        if (item == 1) {
                            started.countDown();
                            return awaitValue(first);
                        }
                        secondFactory.incrementAndGet();
                        return awaitValue(OresFuture.completed(22));
                    });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertEquals(0, secondFactory.get());
            first.completeFromRuntime(11);
            assertEquals(List.of(11,22), handle.result().get(3, TimeUnit.SECONDS));
            assertNull(handle.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(1, secondFactory.get());
        }
    }

    @Test void childFailureStopsPendingFactoriesAndCancelsSibling() throws Exception {
        OresFuture<Integer> never = new OresFuture<>();
        AtomicInteger lateFactories = new AtomicInteger();
        IllegalStateException boom = new IllegalStateException("failed");
        try (OresScheduler scheduler = new OresScheduler(3)) {
            var handle = TaskMapParallel.mapLimit(
                    scheduler, List.of(0,1,2,3,4), 2, index -> {
                        if (index == 0) return awaitValue(never);
                        if (index == 1) return awaitValue(OresFuture.failed(boom));
                        lateFactories.incrementAndGet();
                        return awaitValue(OresFuture.completed(index));
                    });
            var failure = assertThrows(ExecutionException.class,
                    () -> handle.result().get(4, TimeUnit.SECONDS));
            assertSame(boom, failure.getCause());
            assertNull(handle.getDone().get(4, TimeUnit.SECONDS));
            assertEquals(0, lateFactories.get());
            assertFalse(never.isCancelled(), "borrowed Future must remain live");
        }
    }

    @Test void cancellationWaitsForPhysicallyExecutingChild() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger exited = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(3)) {
            var handle = TaskMapParallel.mapLimit(
                    scheduler, List.of(42), 1, value -> resume -> {
                        entered.countDown();
                        try {
                            if (!release.await(3, TimeUnit.SECONDS)) {
                                throw new AssertionError("test release timeout");
                            }
                        } finally {
                            exited.incrementAndGet();
                        }
                        return OresScheduler.done(value);
                    });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(handle.cancel("stop"));
                assertFalse(handle.getDone().isDone(),
                        "physical child still running: no arena reclamation");
            } finally {
                release.countDown();
            }
            assertNull(handle.getDone().get(4, TimeUnit.SECONDS));
            assertEquals(1, exited.get());
        }
    }

    @Test void mapperConstructionRunsOnSchedulerNotCallingThread() throws Exception {
        AtomicReference<Thread> factoryCarrier = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            var handle = TaskMapParallel.mapLimit(
                    scheduler, List.of(1), 1, value -> {
                        factoryCarrier.set(Thread.currentThread());
                        return awaitValue(OresFuture.completed(value + 2));
                    });
            assertEquals(List.of(3), handle.result().get(3, TimeUnit.SECONDS));
            assertNull(handle.getDone().get(3, TimeUnit.SECONDS));
            assertNotSame(caller, factoryCarrier.get());
        }
    }

    @Test void invalidLimitsRejectBeforeAnyFactory() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicInteger entered = new AtomicInteger();
            assertThrows(IllegalArgumentException.class,
                    () -> TaskMapParallel.mapLimit(
                            scheduler, List.of(1,2), 0, i -> {
                                entered.incrementAndGet();
                                return awaitValue(OresFuture.completed(i));
                            }));
            assertThrows(IllegalArgumentException.class,
                    () -> TaskMapParallel.mapLimit(
                            scheduler, List.of(1), 65_537,
                            i -> awaitValue(OresFuture.completed(i))));
            assertEquals(0, entered.get());
        }
    }

    @Test void oneWorkerDoesNotParkOnWaitingChildren() throws Exception {
        OresFuture<Integer> first = new OresFuture<>();
        OresFuture<Integer> second = new OresFuture<>();
        CountDownLatch twoAdmitted = new CountDownLatch(2);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            var handle = TaskMapParallel.mapLimit(
                    scheduler, List.of(0,1), 2, i -> {
                        twoAdmitted.countDown();
                        return awaitValue(i == 0 ? first : second);
                    });
            assertTrue(twoAdmitted.await(3, TimeUnit.SECONDS));
            second.completeFromRuntime(20);
            first.completeFromRuntime(10);
            assertEquals(List.of(10,20), handle.result().get(4, TimeUnit.SECONDS));
            assertNull(handle.getDone().get(4, TimeUnit.SECONDS));
        }
    }

    @Test void thousandsOfAlreadyCompletedTasksDoNotRecurse() throws Exception {
        final int n = 512;
        Integer[] values = new Integer[n];
        for (int i = 0; i < n; i++) values[i] = i;
        try (OresScheduler scheduler = new OresScheduler(2)) {
            var handle = TaskMapParallel.mapLimit(
                    scheduler, java.util.Arrays.asList(values), 32,
                    i -> awaitValue(OresFuture.completed(i * 2)));
            List<Integer> results = handle.result().get(10, TimeUnit.SECONDS);
            assertEquals(n, results.size());
            for (int i = 0; i < n; i++) {
                assertEquals(Integer.valueOf(i * 2), results.get(i));
            }
            assertNull(handle.getDone().get(10, TimeUnit.SECONDS));
        }
    }
}
