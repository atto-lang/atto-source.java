package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the scheduler primitives used by the nested-Future flatten task. */
final class NestedFutureSchedulerContractTest {

    @Test
    void cancellingDerivedWaiterDetachesOuterWithoutCancellingProducer() throws Exception {
        OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
        OresFuture<Integer> inner = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> derived = scheduler.start(nestedTask(outer));
            awaitWaiters(outer, 1);
            assertTrue(derived.cancel(false));
            awaitWaiters(outer, 0);
            assertFalse(OresScheduler.hasAwaitDependency(derived),
                    "cancelled outer await retained a dependency");
            assertFalse(outer.isCancelled());
            assertTrue(outer.completeFromRuntime(inner));
            assertTrue(inner.completeFromRuntime(42));
            assertTrue(derived.isCancelled());
        }
    }

    @Test
    void cancellingDerivedWaiterDetachesInnerWithoutCancellingProducer() throws Exception {
        OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
        OresFuture<Integer> inner = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> derived = scheduler.start(nestedTask(outer));
            awaitWaiters(outer, 1);
            assertTrue(outer.completeFromRuntime(inner));
            awaitWaiters(inner, 1);
            assertTrue(derived.cancel(false));
            awaitWaiters(inner, 0);
            assertFalse(OresScheduler.hasAwaitDependency(derived),
                    "cancelled inner await retained a dependency");
            assertFalse(inner.isCancelled());
            assertTrue(inner.completeFromRuntime(42));
            assertTrue(derived.isCancelled());
        }
    }

    @Test
    void twoFutureCycleAcrossDistinctSchedulersFailsAndDetaches() throws Exception {
        assertCycleFailsAndDetaches(2);
    }

    @Test
    void threeFutureCycleAcrossDistinctSchedulersFailsAndDetaches() throws Exception {
        assertCycleFailsAndDetaches(3);
    }

    private static void assertCycleFailsAndDetaches(int length) throws Exception {
        AtomicReferenceArray<OresFuture<Integer>> completions =
                new AtomicReferenceArray<>(length);
        java.util.List<OresFuture<Void>> gates = new java.util.ArrayList<>();
        java.util.List<OresScheduler> schedulers = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < length; i++) {
                gates.add(new OresFuture<>());
                schedulers.add(new OresScheduler(1));
            }
            for (int i = 0; i < length; i++) {
                final int position = i;
                OresFuture<Integer> task = schedulers.get(i).start(
                        new OresScheduler.Task<>() {
                            int step;
                            @Override
                            public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                                if (resume.failure() != null) {
                                    throw new IllegalStateException("upstream await failed",
                                            resume.failure());
                                }
                                if (step++ == 0) return OresScheduler.await(gates.get(position));
                                if (step == 2) return OresScheduler.await(
                                        completions.get((position + 1) % length));
                                return OresScheduler.done((Integer) resume.value());
                            }
                        });
                completions.set(i, task);
            }
            for (OresFuture<Void> gate : gates) {
                awaitWaiters(gate, 1);
            }
            for (OresFuture<Void> gate : gates) {
                assertTrue(gate.completeFromRuntime(null));
            }
            for (int i = 0; i < length; i++) {
                OresFuture<Integer> completion = completions.get(i);
                assertThrows(ExecutionException.class,
                        () -> completion.get(3, TimeUnit.SECONDS));
                assertFalse(OresScheduler.hasAwaitDependency(completion),
                        "cycle graph retained task " + i);
                assertEquals(0, completion.pendingRuntimeWaiterCount(),
                        "cyclic task retained a completion waiter");
            }
        } finally {
            for (OresScheduler scheduler : schedulers) scheduler.close();
        }
    }

    @Test
    void taskCannotAwaitItsOwnCompletionFuture() throws Exception {
        OresFuture<Void> gate = new OresFuture<>();
        AtomicReference<OresFuture<Integer>> owner = new AtomicReference<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> derived = scheduler.start(new OresScheduler.Task<>() {
                private int pc;

                @Override
                public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    if (pc++ == 0) {
                        return OresScheduler.await(gate);
                    }
                    return OresScheduler.await(owner.get());
                }
            });
            owner.set(derived);
            assertTrue(gate.completeFromRuntime(null));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> derived.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException,
                    failure.toString());
            assertTrue(failure.getCause().getMessage().contains(
                            "cannot await its own completion Future"),
                    failure.toString());
            assertEquals(0, derived.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void outerFailureStopsBeforeSubscribingToInnerFuture() throws Exception {
        OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
        OresFuture<Integer> inner = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> derived = scheduler.start(nestedTask(outer));
            awaitWaiters(outer, 1);
            assertTrue(outer.failFromRuntime(new IllegalStateException("outer-failed")));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> derived.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("nested source failed"),
                    failure.toString());
            assertEquals(0, inner.pendingRuntimeWaiterCount());
            assertFalse(inner.isDone());
            assertEquals(0, outer.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void innerFailurePropagatesWithoutRetryingOrCancellingProducer() throws Exception {
        OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
        OresFuture<Integer> inner = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> derived = scheduler.start(nestedTask(outer));
            awaitWaiters(outer, 1);
            assertTrue(outer.completeFromRuntime(inner));
            awaitWaiters(inner, 1);
            assertTrue(inner.failFromRuntime(new IllegalArgumentException("inner-failed")));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> derived.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("nested source failed"),
                    failure.toString());
            assertEquals(0, inner.pendingRuntimeWaiterCount());
            assertFalse(outer.isCancelled());
            assertFalse(inner.isCancelled());
        }
    }

    @Test
    void eachFutureLayerResumesThroughOwningScheduler() throws Exception {
        OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
        OresFuture<Integer> inner = new OresFuture<>();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            long[] dispatches = new long[3];
            Object[] domains = new Object[3];
            OresFuture<Integer> derived = scheduler.start(new OresScheduler.Task<>() {
                int pc;
                @Override
                public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    assertSame(scheduler, OresScheduler.current());
                    dispatches[pc] = OresScheduler.currentDispatchId();
                    domains[pc] = OresScheduler.currentTaskDomain();
                    if (pc++ == 0) return OresScheduler.await(outer);
                    if (pc == 2) return OresScheduler.await((OresFuture<?>) resume.value());
                    return OresScheduler.done((Integer) resume.value());
                }
            });
            awaitWaiters(outer, 1);
            assertTrue(outer.completeFromRuntime(inner));
            awaitWaiters(inner, 1);
            assertTrue(inner.completeFromRuntime(42));
            assertEquals(42, derived.get(3, TimeUnit.SECONDS));
            assertNotEquals(0L, dispatches[0]);
            assertNotEquals(dispatches[0], dispatches[1]);
            assertNotEquals(dispatches[1], dispatches[2]);
            assertSame(domains[0], domains[1]);
            assertSame(domains[1], domains[2]);
        }
    }

    private static OresScheduler.Task<Integer> nestedTask(
            OresFuture<OresFuture<Integer>> outer) {
        return new OresScheduler.Task<>() {
            int pc;
            @Override
            public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                if (resume.failure() != null) {
                    throw new IllegalStateException("nested source failed", resume.failure());
                }
                if (pc++ == 0) return OresScheduler.await(outer);
                if (pc == 2) return OresScheduler.await((OresFuture<?>) resume.value());
                return OresScheduler.done((Integer) resume.value());
            }
        };
    }

    private static void awaitWaiters(OresFuture<?> future, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (future.pendingRuntimeWaiterCount() != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertEquals(expected, future.pendingRuntimeWaiterCount());
    }
}
