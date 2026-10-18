package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

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
            assertFalse(inner.isCancelled());
            assertTrue(inner.completeFromRuntime(42));
            assertTrue(derived.isCancelled());
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
