package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Host-only composition contracts. Guest-language exposure is a separate gate. */
public class FutureCombinatorsTest {
    @Test void emptyAllAndAllSettledAreSuccessful() {
        assertEquals(List.of(), FutureCombinators.<Integer>all(List.of()).join());
        assertEquals(List.of(), FutureCombinators.<Integer>allSettled(List.of()).join());
        assertThrows(IllegalArgumentException.class,
                () -> FutureCombinators.<Integer>race(List.of()));
    }

    @Test void allKeepsInputOrderAndNullPayloads() throws Exception {
        OresFuture<Integer> a = new OresFuture<>();
        OresFuture<Integer> b = new OresFuture<>();
        OresFuture<Integer> c = new OresFuture<>();
        OresFuture<List<Integer>> both = FutureCombinators.all(List.of(a, b, c));
        c.completeFromRuntime(33);
        b.completeFromRuntime(null);
        assertFalse(both.isDone());
        a.completeFromRuntime(11);
        assertEquals(Arrays.asList(11, null, 33),
                both.get(2, TimeUnit.SECONDS));
        assertEquals(0, a.pendingRuntimeWaiterCount());
        assertEquals(0, b.pendingRuntimeWaiterCount());
        assertEquals(0, c.pendingRuntimeWaiterCount());
    }

    @Test void allFailsFastDetachingButNeverCancellingOtherSources() {
        OresFuture<Integer> stillRunning = new OresFuture<>();
        OresFuture<Integer> failing = new OresFuture<>();
        OresFuture<List<Integer>> aggregate =
                FutureCombinators.all(List.of(stillRunning, failing));
        IllegalStateException failure = new IllegalStateException("boom");
        failing.failFromRuntime(failure);
        ExecutionException result = assertThrows(ExecutionException.class,
                aggregate::get);
        assertSame(failure, result.getCause());
        assertFalse(stillRunning.isDone());
        assertEquals(0, stillRunning.pendingRuntimeWaiterCount());
    }

    @Test void cancellingAggregateDoesNotCancelSharedSources() {
        OresFuture<Integer> left = new OresFuture<>();
        OresFuture<Integer> right = new OresFuture<>();
        OresFuture<List<Integer>> aggregate =
                FutureCombinators.all(List.of(left, right));
        assertTrue(aggregate.cancel(false));
        assertTrue(aggregate.isCancelled());
        assertFalse(left.isDone());
        assertFalse(right.isDone());
        assertEquals(0, left.pendingRuntimeWaiterCount());
        assertEquals(0, right.pendingRuntimeWaiterCount());
        left.completeFromRuntime(1);
        right.completeFromRuntime(2);
        assertTrue(aggregate.isCancelled());
    }

    @Test void cancelledSourceCancelsAggregateWithoutTouchingSibling() {
        OresFuture<Integer> left = new OresFuture<>();
        OresFuture<Integer> right = new OresFuture<>();
        OresFuture<List<Integer>> aggregate =
                FutureCombinators.all(List.of(left, right));
        assertTrue(left.cancel(false));
        assertTrue(aggregate.isCancelled());
        assertFalse(right.isCancelled());
        assertEquals(0, right.pendingRuntimeWaiterCount());
    }

    @Test void allSettledWaitsForFailuresAndCancellation() {
        OresFuture<Integer> success = new OresFuture<>();
        OresFuture<Integer> failure = new OresFuture<>();
        OresFuture<Integer> cancelled = new OresFuture<>();
        OresFuture<List<FutureCombinators.Outcome<Integer>>> aggregate =
                FutureCombinators.allSettled(List.of(success, failure, cancelled));
        CancellationException ordinaryFailure = new CancellationException("raised");
        failure.failFromRuntime(ordinaryFailure);
        cancelled.cancel(false);
        assertFalse(aggregate.isDone());
        success.completeFromRuntime(42);
        var outcomes = aggregate.join();
        assertEquals(3, outcomes.size());
        assertEquals(FutureCombinators.Status.SUCCESS, outcomes.get(0).status());
        assertEquals(Integer.valueOf(42), outcomes.get(0).value());
        assertNull(outcomes.get(0).failure());
        assertEquals(FutureCombinators.Status.FAILURE, outcomes.get(1).status(),
                "user-raised CancellationException is NOT cancellation authority");
        assertSame(ordinaryFailure, outcomes.get(1).failure());
        assertEquals(FutureCombinators.Status.CANCELLED, outcomes.get(2).status());
        assertInstanceOf(CancellationException.class, outcomes.get(2).failure());
        assertEquals(0, success.pendingRuntimeWaiterCount());
        assertEquals(0, failure.pendingRuntimeWaiterCount());
        assertEquals(0, cancelled.pendingRuntimeWaiterCount());
    }

    @Test void raceSettlesFirstAndDetachesLosers() {
        OresFuture<Integer> slow = new OresFuture<>();
        OresFuture<Integer> fast = new OresFuture<>();
        OresFuture<Integer> result = FutureCombinators.race(List.of(slow, fast));
        fast.completeFromRuntime(23);
        assertEquals(Integer.valueOf(23), result.join());
        assertFalse(slow.isDone());
        assertEquals(0, slow.pendingRuntimeWaiterCount());
        slow.completeFromRuntime(99);
        assertEquals(Integer.valueOf(23), result.join());
    }

    @Test void alreadySettledRaceUsesInputOrderAndLeavesNoListeners() {
        OresFuture<Integer> first = OresFuture.completed(7);
        OresFuture<Integer> second = OresFuture.completed(9);
        OresFuture<Integer> result = FutureCombinators.race(List.of(first, second));
        assertEquals(Integer.valueOf(7), result.join());
        assertEquals(0, first.pendingRuntimeWaiterCount());
        assertEquals(0, second.pendingRuntimeWaiterCount());
    }

    @Test void prefailedAllDoesNotSubscribeLaterInput() {
        OresFuture<Integer> failure =
                OresFuture.failed(new IllegalArgumentException("pre-settled"));
        OresFuture<Integer> pending = new OresFuture<>();
        OresFuture<List<Integer>> result =
                FutureCombinators.all(List.of(failure, pending));
        assertTrue(result.isCompletedExceptionally());
        assertEquals(0, pending.pendingRuntimeWaiterCount());
        assertFalse(pending.isDone());
    }

    @Test void racingSourceSettlementsSettleAllExactlyOnce() throws Exception {
        for (int round = 0; round < 100; round++) {
            OresFuture<Integer> a = new OresFuture<>();
            OresFuture<Integer> b = new OresFuture<>();
            OresFuture<List<Integer>> joined = FutureCombinators.all(List.of(a, b));
            CountDownLatch gate = new CountDownLatch(1);
            AtomicReference<Throwable> threadFailure = new AtomicReference<>();
            Thread t1 = Thread.ofPlatform().start(() -> {
                try { gate.await(); a.completeFromRuntime(1); }
                catch (Throwable failure) { threadFailure.compareAndSet(null, failure); }
            });
            Thread t2 = Thread.ofPlatform().start(() -> {
                try { gate.await(); b.completeFromRuntime(2); }
                catch (Throwable failure) { threadFailure.compareAndSet(null, failure); }
            });
            gate.countDown();
            try {
                assertEquals(List.of(1, 2), joined.get(3, TimeUnit.SECONDS));
                assertNull(threadFailure.get());
            } finally {
                t1.join(3_000);
                t2.join(3_000);
            }
            assertEquals(0, a.pendingRuntimeWaiterCount());
            assertEquals(0, b.pendingRuntimeWaiterCount());
        }
    }

    @Test void anyReturnsFirstSuccessNotFirstFailure() throws Exception {
        OresFuture<Integer> first = new OresFuture<>();
        OresFuture<Integer> second = new OresFuture<>();
        OresFuture<Integer> third = new OresFuture<>();
        OresFuture<Integer> result = FutureCombinators.any(
                List.of(first, second, third));
        first.failFromRuntime(new IllegalStateException("failed first"));
        assertFalse(result.isDone());
        third.completeFromRuntime(30);
        assertEquals(Integer.valueOf(30), result.get(3, TimeUnit.SECONDS));
        assertFalse(second.isDone());
        assertEquals(0, second.pendingRuntimeWaiterCount());
    }

    @Test void anyAllFailedReportsCausesInInputOrder() throws Exception {
        OresFuture<Integer> one = new OresFuture<>();
        OresFuture<Integer> two = new OresFuture<>();
        OresFuture<Integer> three = new OresFuture<>();
        Throwable a = new IllegalArgumentException("a");
        Throwable b = new IllegalStateException("b");
        OresFuture<Integer> result = FutureCombinators.any(
                List.of(one, two, three));
        three.failFromRuntime(b);
        one.failFromRuntime(a);
        two.cancel(false);
        var thrown = assertThrows(ExecutionException.class,
                () -> result.get(3, TimeUnit.SECONDS));
        var aggregate = assertInstanceOf(
                FutureCombinators.AnyFailedException.class,
                thrown.getCause());
        assertSame(a, aggregate.failures().get(0));
        assertInstanceOf(CancellationException.class,
                aggregate.failures().get(1));
        assertSame(b, aggregate.failures().get(2));
        assertEquals(3, aggregate.getSuppressed().length);
    }

    @Test void anyAllCancelledPropagatesAuthenticatedCancellation() {
        OresFuture<Integer> one = new OresFuture<>();
        OresFuture<Integer> two = new OresFuture<>();
        OresFuture<Integer> result = FutureCombinators.any(List.of(one, two));
        one.cancel(false);
        assertFalse(result.isDone());
        two.cancel(false);
        assertTrue(result.isCancelled());
        assertEquals(0, one.pendingRuntimeWaiterCount());
        assertEquals(0, two.pendingRuntimeWaiterCount());
    }

    @Test void anyEmptyRejectedAndConsumerCancellationDetachesOnly() {
        assertThrows(IllegalArgumentException.class,
                () -> FutureCombinators.<Integer>any(List.of()));
        OresFuture<Integer> source = new OresFuture<>();
        OresFuture<Integer> result = FutureCombinators.any(List.of(source));
        assertTrue(result.cancel(false));
        assertEquals(0, source.pendingRuntimeWaiterCount());
        assertFalse(source.isCancelled());
    }

    @Test void mapperRunsOnSchedulerNotProducerCompletionThread() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> producer = new OresFuture<>();
            AtomicReference<Thread> producerThread = new AtomicReference<>();
            AtomicReference<Thread> mappingThread = new AtomicReference<>();
            OresFuture<Integer> result = FutureCombinators.map(
                    scheduler, producer, value -> {
                        mappingThread.set(Thread.currentThread());
                        return value * 2;
                    });
            Thread publisher = Thread.ofPlatform().start(() -> {
                producerThread.set(Thread.currentThread());
                producer.completeFromRuntime(17);
            });
            publisher.join(3_000);
            assertFalse(publisher.isAlive());
            assertEquals(Integer.valueOf(34), result.get(3, TimeUnit.SECONDS));
            assertNotNull(mappingThread.get());
            assertNotSame(producerThread.get(), mappingThread.get());
        }
    }

    @Test void flatMapCanAwaitANestedFutureWithoutBlockingCarrier() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresFuture<String> dependent = new OresFuture<>();
            CountDownLatch mapperEntered = new CountDownLatch(1);
            OresFuture<String> flattened = FutureCombinators.flatMap(
                    scheduler, source, value -> {
                        assertEquals(Integer.valueOf(3), value);
                        mapperEntered.countDown();
                        return dependent;
                    });
            source.completeFromRuntime(3);
            assertTrue(mapperEntered.await(3, TimeUnit.SECONDS));
            assertFalse(flattened.isDone());
            dependent.completeFromRuntime("flattened");
            assertEquals("flattened", flattened.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void sourceFailureSkipsMapFunction() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            AtomicInteger invoked = new AtomicInteger();
            OresFuture<Integer> result = FutureCombinators.map(
                    scheduler, source, value -> {
                        invoked.incrementAndGet();
                        return value + 1;
                    });
            IllegalStateException failure = new IllegalStateException("source failed");
            source.failFromRuntime(failure);
            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> result.get(3, TimeUnit.SECONDS)).getCause());
            assertEquals(0, invoked.get());
        }
    }

    @Test void zipCombinesHeterogeneousTasksInInputOrder() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> left = new OresFuture<>();
            OresFuture<String> right = new OresFuture<>();
            OresFuture<FutureCombinators.Pair<Integer, String>> zipped =
                    FutureCombinators.zip(scheduler, left, right);
            right.completeFromRuntime("ok");
            assertFalse(zipped.isDone());
            left.completeFromRuntime(19);
            assertEquals(new FutureCombinators.Pair<>(19, "ok"),
                    zipped.get(3, TimeUnit.SECONDS));
            assertEquals(0, left.pendingRuntimeWaiterCount());
            assertEquals(0, right.pendingRuntimeWaiterCount());
        }
    }

    @Test void cancellingMappedTaskDoesNotRevokeSharedProducer() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresFuture<Integer> mapped =
                    FutureCombinators.map(scheduler, source, v -> v + 3);
            mapped.cancel(false);
            assertTrue(mapped.isCancelled());
            assertFalse(source.isCancelled());
            source.completeFromRuntime(2);
            assertTrue(mapped.isCancelled());
        }
    }

    @Test void cancelledSourceCancelsSchedulerMappedResultButNotOtherObservers()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresFuture<Integer> first = FutureCombinators.map(
                    scheduler, source, value -> value + 1);
            OresFuture<Integer> second = FutureCombinators.map(
                    scheduler, source, value -> value + 2);
            assertTrue(source.cancel(false));
            assertTrue(first.isCancelled());
            assertTrue(second.isCancelled());
            assertEquals(0, source.pendingRuntimeWaiterCount());
        }
    }

    @Test void preCancelledSourceDoesNotRunMappedTask() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> cancelled = new OresFuture<>();
            assertTrue(cancelled.cancel(false));
            AtomicInteger invocations = new AtomicInteger();
            OresFuture<Integer> mapped = FutureCombinators.map(
                    scheduler, cancelled, value -> {
                        invocations.incrementAndGet();
                        return value;
                    });
            assertTrue(mapped.isCancelled());
            assertEquals(0, invocations.get());
            assertEquals(0, cancelled.pendingRuntimeWaiterCount());
        }
    }

    @Test void nestedFlatMapCancelledSourcePropagatesWithoutCancellingProducer()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresFuture<String> nested = new OresFuture<>();
            CountDownLatch mapped = new CountDownLatch(1);
            OresFuture<String> result = FutureCombinators.flatMap(
                    scheduler, source, value -> {
                        mapped.countDown();
                        return nested;
                    });
            source.completeFromRuntime(1);
            assertTrue(mapped.await(3, TimeUnit.SECONDS),
                    "flatMap mapper did not begin");
            // The mapper's latch fires before flatMap installs the inner
            // cancellation watcher. Cancelling immediately is a test race:
            // it can observe the Future before subscription and report a
            // spurious failure. Wait for actual registration instead.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (nested.pendingRuntimeWaiterCount() == 0 &&
                    System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(nested.pendingRuntimeWaiterCount() > 0,
                    "flatMap did not subscribe to its inner Future");
            assertTrue(nested.cancel(false));
            assertThrows(CancellationException.class,
                    () -> result.get(3, TimeUnit.SECONDS),
                    "authenticated inner cancellation must cancel the derived Task");
            assertTrue(result.isCancelled());
            assertEquals(0, nested.pendingRuntimeWaiterCount());
            assertFalse(source.isCancelled(), "flatMap never revokes shared source");
        }
    }

    @Test void guestRaisedCancellationExceptionIsNotAuthenticatedCancellation()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresFuture<Integer> result = FutureCombinators.map(
                    scheduler, source, value -> value);
            CancellationException ordinary = new CancellationException("raised");
            source.failFromRuntime(ordinary);
            assertFalse(source.isCancelled());
            assertFalse(result.isCancelled());
            assertSame(ordinary, assertThrows(ExecutionException.class,
                    () -> result.get(3, TimeUnit.SECONDS)).getCause());
        }
    }
}
