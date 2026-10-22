package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
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
}
